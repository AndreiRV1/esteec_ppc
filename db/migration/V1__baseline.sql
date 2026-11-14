-- FileGrid baseline schema.
-- Applied by Flyway as filegrid_migrator (search_path = filegrid).
-- Conventions:
--   * every table carries `region` (FK to region.code);
--   * enumerations are text + CHECK (easy to extend, maps cleanly to JPA);
--   * checksums are lowercase hex SHA-256;
--   * no plaintext personal data: it only exists inside encrypted archives.

-- =============================================================================
-- Reference data
-- =============================================================================

CREATE TABLE region (
    code text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z_]*$'),
    name text NOT NULL
);
COMMENT ON TABLE region IS 'Rețele Electrice distribution areas. Every other table references this.';

INSERT INTO region (code, name) VALUES
    ('MUNTENIA', 'Muntenia'),   -- București, Ilfov, Giurgiu
    ('BANAT',    'Banat'),      -- Timiș, Arad, Caraș-Severin, Hunedoara
    ('DOBROGEA', 'Dobrogea');   -- Constanța, Tulcea, Călărași, Ialomița

CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

-- =============================================================================
-- Storage policy: where each file category is written. Editable at runtime
-- (PDF §3: "Politica per categorie de date este configurabilă, fără modificarea codului").
-- Meter readings are not here: they are always stored in PostgreSQL.
-- =============================================================================

CREATE TABLE storage_policy (
    category        text        NOT NULL CHECK (category IN ('FAULT_RECORD', 'CONNECTION_DOCUMENTS', 'DRONE_VIDEO')),
    region          text        NOT NULL REFERENCES region (code),
    backend         text        NOT NULL CHECK (backend IN ('DATABASE', 'S3', 'FTP')),
    target          text,       -- S3 bucket or FTP directory; NULL for DATABASE
    encrypt_at_rest boolean     NOT NULL DEFAULT false,
    max_size_bytes  bigint      NOT NULL CHECK (max_size_bytes > 0),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL DEFAULT current_user,
    PRIMARY KEY (category, region),
    CONSTRAINT storage_policy_target_matches_backend CHECK ((backend = 'DATABASE') = (target IS NULL)),
    -- bytea tops out at 1 GB; keep DB-stored files small.
    CONSTRAINT storage_policy_db_size_limit CHECK (backend <> 'DATABASE' OR max_size_bytes <= 104857600),
    -- Personal data must never be written in clear (PDF §4, Securitate).
    CONSTRAINT storage_policy_pii_encrypted CHECK (category <> 'CONNECTION_DOCUMENTS' OR encrypt_at_rest)
);
CREATE TRIGGER storage_policy_updated_at BEFORE UPDATE ON storage_policy
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO storage_policy (category, region, backend, target, encrypt_at_rest, max_size_bytes)
SELECT p.category, r.code, p.backend, p.target, p.encrypt, p.max_size
FROM region r
CROSS JOIN (VALUES
    ('FAULT_RECORD',         'FTP', '/files/fault-records',          false,       52428800::bigint),  -- 50 MB
    ('CONNECTION_DOCUMENTS', 'S3',  'filegrid-connection-documents', true,        52428800::bigint),  -- 50 MB
    ('DRONE_VIDEO',          'S3',  'filegrid-drone-videos',         false, 10737418240::bigint)      -- 10 GB
) AS p (category, backend, target, encrypt, max_size);

-- =============================================================================
-- Stored files (fault records, connection archives, drone videos)
-- One row per physical object, wherever the policy put it.
-- =============================================================================

CREATE TABLE stored_file (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    region            text        NOT NULL REFERENCES region (code),
    category          text        NOT NULL CHECK (category IN ('FAULT_RECORD', 'CONNECTION_DOCUMENTS', 'DRONE_VIDEO')),
    backend           text        NOT NULL CHECK (backend IN ('DATABASE', 'S3', 'FTP')),
    location          text,       -- S3: "<bucket>/<key>", FTP: absolute path; NULL for DATABASE
    original_filename text        NOT NULL,
    content_type      text        NOT NULL,
    size_bytes        bigint      CHECK (size_bytes >= 0),
    -- Integrity: sha256 of the bytes the user sent; stored_sha256 of the bytes
    -- actually written (differs only when encrypted). Both verified on read.
    sha256            text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    stored_sha256     text        CHECK (stored_sha256 ~ '^[0-9a-f]{64}$'),
    -- Envelope encryption: the data key is stored wrapped by a master key that
    -- lives outside the database (key id = which master key version).
    encryption        text        NOT NULL DEFAULT 'NONE' CHECK (encryption IN ('NONE', 'AES_256_GCM')),
    encryption_key_id text,
    wrapped_dek       bytea,
    status            text        NOT NULL DEFAULT 'UPLOADING'
                                  CHECK (status IN ('UPLOADING', 'STORED', 'FAILED', 'CORRUPTED', 'DELETED')),
    -- Resumable uploads (large videos over unstable links).
    upload_id         text,       -- S3 multipart upload id while UPLOADING
    bytes_received    bigint      NOT NULL DEFAULT 0 CHECK (bytes_received >= 0),
    uploaded_by       text        NOT NULL,
    trace_id          text,       -- W3C trace id of the request that created it
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    stored_at         timestamptz,
    last_verified_at  timestamptz,
    CONSTRAINT stored_file_id_region_uk UNIQUE (id, region),
    CONSTRAINT stored_file_location_matches_backend CHECK ((backend = 'DATABASE') = (location IS NULL)),
    CONSTRAINT stored_file_encryption_consistent CHECK (
        (encryption = 'NONE') = (wrapped_dek IS NULL AND encryption_key_id IS NULL)),
    CONSTRAINT stored_file_stored_is_complete CHECK (
        status <> 'STORED'
        OR (size_bytes IS NOT NULL AND sha256 IS NOT NULL AND stored_sha256 IS NOT NULL AND stored_at IS NOT NULL)),
    CONSTRAINT stored_file_pii_encrypted CHECK (category <> 'CONNECTION_DOCUMENTS' OR encryption <> 'NONE')
);
CREATE UNIQUE INDEX stored_file_backend_location_uk ON stored_file (backend, location) WHERE location IS NOT NULL;
CREATE INDEX stored_file_browse_idx ON stored_file (category, region, created_at DESC);
CREATE INDEX stored_file_unfinished_idx ON stored_file (updated_at) WHERE status IN ('UPLOADING', 'FAILED', 'CORRUPTED');
CREATE TRIGGER stored_file_updated_at BEFORE UPDATE ON stored_file
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
COMMENT ON TABLE stored_file IS 'Physical object metadata for every uploaded file, whatever backend holds it.';

-- Bytes of files whose policy backend is DATABASE.
CREATE TABLE stored_file_blob (
    file_id uuid  PRIMARY KEY,
    region  text  NOT NULL REFERENCES region (code),
    content bytea NOT NULL,
    FOREIGN KEY (file_id, region) REFERENCES stored_file (id, region) ON DELETE CASCADE
);
-- Uploaded files are usually already compressed or encrypted: skip TOAST compression.
ALTER TABLE stored_file_blob ALTER COLUMN content SET STORAGE EXTERNAL;

-- =============================================================================
-- Smart meters ("contoare")
-- =============================================================================

CREATE TABLE meter (
    id           text        PRIMARY KEY,   -- serial number reported by the device
    region       text        NOT NULL REFERENCES region (code),
    county       text        NOT NULL,
    locality     text        NOT NULL,
    latitude     numeric(9, 6) CHECK (latitude BETWEEN -90 AND 90),
    longitude    numeric(9, 6) CHECK (longitude BETWEEN -180 AND 180),
    installed_at timestamptz NOT NULL DEFAULT now(),
    active       boolean     NOT NULL DEFAULT true,
    CONSTRAINT meter_id_region_uk UNIQUE (id, region)
);
CREATE INDEX meter_area_idx ON meter (region, county, locality);

-- Raw readings exactly as received (1-50 KB each). Source of truth for billing.
-- No loss: the app role cannot DELETE. No duplicates: (meter_id, message_id) is
-- unique, and the meter reuses message_id on every retry of the same reading.
CREATE TABLE meter_reading_raw (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region            text        NOT NULL,
    meter_id          text        NOT NULL,
    message_id        uuid        NOT NULL,
    period_start      timestamptz NOT NULL,
    period_end        timestamptz NOT NULL,
    content_type      text        NOT NULL,
    payload           bytea       COMPRESSION lz4 NOT NULL,
    size_bytes        integer     GENERATED ALWAYS AS (octet_length(payload)) STORED,
    sha256            text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    received_at       timestamptz NOT NULL DEFAULT now(),
    processing_status text        NOT NULL DEFAULT 'PENDING'
                                  CHECK (processing_status IN ('PENDING', 'PROCESSED', 'REJECTED')),
    processed_at      timestamptz,
    processing_error  text,
    trace_id          text,
    FOREIGN KEY (meter_id, region) REFERENCES meter (id, region),
    CONSTRAINT meter_reading_raw_idempotency_uk UNIQUE (meter_id, message_id),
    CONSTRAINT meter_reading_raw_period_valid CHECK (period_end > period_start),
    CONSTRAINT meter_reading_raw_max_size CHECK (octet_length(payload) <= 1048576),
    CONSTRAINT meter_reading_raw_processed_consistent CHECK ((processing_status = 'PENDING') = (processed_at IS NULL)),
    CONSTRAINT meter_reading_raw_rejected_has_error CHECK (processing_status <> 'REJECTED' OR processing_error IS NOT NULL)
);
-- Work queue for the preprocessor: SELECT ... FOR UPDATE SKIP LOCKED.
CREATE INDEX meter_reading_raw_pending_idx ON meter_reading_raw (received_at) WHERE processing_status = 'PENDING';
CREATE INDEX meter_reading_raw_received_brin ON meter_reading_raw USING brin (received_at);
CREATE INDEX meter_reading_raw_meter_period_idx ON meter_reading_raw (meter_id, period_start);

-- Preprocessed readings: one row per meter per interval, parsed and validated.
CREATE TABLE meter_reading (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region           text        NOT NULL,
    meter_id         text        NOT NULL,
    raw_id           bigint      NOT NULL REFERENCES meter_reading_raw (id),
    interval_start   timestamptz NOT NULL,
    interval_minutes smallint    NOT NULL DEFAULT 15 CHECK (interval_minutes > 0),
    register_kwh     numeric(14, 3) NOT NULL CHECK (register_kwh >= 0),  -- cumulative index
    consumption_kwh  numeric(12, 3) NOT NULL,                            -- delta vs previous interval
    voltage_v        numeric(6, 2),
    quality          text        NOT NULL DEFAULT 'VALID' CHECK (quality IN ('VALID', 'ESTIMATED', 'ANOMALY')),
    anomaly_reason   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (meter_id, region) REFERENCES meter (id, region),
    -- Billing safety net: an interval can be counted only once, even if the
    -- same measurement arrives in two different raw messages.
    CONSTRAINT meter_reading_interval_uk UNIQUE (meter_id, interval_start),
    CONSTRAINT meter_reading_anomaly_has_reason CHECK ((quality = 'ANOMALY') = (anomaly_reason IS NOT NULL))
);
CREATE INDEX meter_reading_region_time_idx ON meter_reading (region, interval_start);
CREATE INDEX meter_reading_raw_id_idx ON meter_reading (raw_id);
CREATE INDEX meter_reading_anomaly_idx ON meter_reading (region, interval_start) WHERE quality = 'ANOMALY';

-- =============================================================================
-- Fault records ("avarii") from transformer substations
-- =============================================================================

CREATE TABLE fault_record (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    region             text        NOT NULL,
    county             text        NOT NULL,
    locality           text,
    substation_code    text        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    restored_at        timestamptz,
    severity           text        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    cause              text,
    affected_customers integer     CHECK (affected_customers >= 0),
    description        text,
    file_id            uuid        NOT NULL UNIQUE,
    reported_by        text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (region) REFERENCES region (code),
    FOREIGN KEY (file_id, region) REFERENCES stored_file (id, region),
    CONSTRAINT fault_record_restore_after_occurrence CHECK (restored_at IS NULL OR restored_at >= occurred_at)
);
CREATE INDEX fault_record_region_time_idx ON fault_record (region, occurred_at DESC);
CREATE INDEX fault_record_area_idx ON fault_record (region, county, locality);
CREATE INDEX fault_record_substation_idx ON fault_record (substation_code, occurred_at DESC);

-- =============================================================================
-- Drone inspection videos
-- =============================================================================

CREATE TABLE drone_video (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    region            text        NOT NULL,
    county            text        NOT NULL,
    line_code         text        NOT NULL,   -- inspected power line
    drone_id          text,
    flight_started_at timestamptz NOT NULL,
    duration_seconds  integer     CHECK (duration_seconds > 0),
    notes             text,
    file_id           uuid        NOT NULL UNIQUE,
    uploaded_by       text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (region) REFERENCES region (code),
    FOREIGN KEY (file_id, region) REFERENCES stored_file (id, region)
);
CREATE INDEX drone_video_region_time_idx ON drone_video (region, flight_started_at DESC);
CREATE INDEX drone_video_line_idx ON drone_video (line_code, flight_started_at DESC);

-- =============================================================================
-- Clients: grid connection requests ("racordare")
-- The applicant uploads cerere + act de identitate + aviz; FileGrid zips them,
-- encrypts the zip and stores it per policy. Personal data (name, CNP, CI
-- series/number, addresses, phone) exists only inside that encrypted archive.
-- =============================================================================

CREATE TABLE client (
    client_id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    region             text        NOT NULL REFERENCES region (code),
    applicant_type     text        NOT NULL CHECK (applicant_type IN ('PERSOANA_FIZICA', 'PERSOANA_JURIDICA')),
    request_type       text        NOT NULL
                                   CHECK (request_type IN ('LOC_CONSUM_NOU', 'SPOR_PUTERE', 'SEPARARE', 'ACTUALIZARE_DATE')),
    -- Consumption place, at a granularity that is not personal data on its own.
    county             text        NOT NULL,
    locality           text        NOT NULL,
    requested_power_kw numeric(10, 2) CHECK (requested_power_kw > 0),
    -- HMAC-SHA256(CNP or CUI, server-side secret): find a returning applicant or
    -- answer an audit query without storing the identifier itself.
    identity_hmac      text        NOT NULL CHECK (identity_hmac ~ '^[0-9a-f]{64}$'),
    archive_file_id    uuid        NOT NULL UNIQUE,
    status             text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    status_reason      text,
    reviewed_by        text,
    reviewed_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (archive_file_id, region) REFERENCES stored_file (id, region),
    CONSTRAINT client_id_region_uk UNIQUE (client_id, region),
    CONSTRAINT client_review_consistent CHECK (
        (status = 'PENDING') = (reviewed_by IS NULL AND reviewed_at IS NULL)),
    CONSTRAINT client_rejection_has_reason CHECK (status <> 'REJECTED' OR status_reason IS NOT NULL)
);
CREATE INDEX client_queue_idx ON client (region, status, created_at);
CREATE INDEX client_identity_idx ON client (identity_hmac);
CREATE TRIGGER client_updated_at BEFORE UPDATE ON client
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Manifest of the archive: one row per document inside the zip, so each
-- document can be verified individually at audit time.
CREATE TABLE client_document (
    id            uuid   PRIMARY KEY DEFAULT uuidv7(),
    client_id     uuid   NOT NULL,
    region        text   NOT NULL,
    document_type text   NOT NULL CHECK (document_type IN (
                      'CERERE_RACORDARE', 'ACT_IDENTITATE', 'AVIZ_AMPLASAMENT', 'CERTIFICAT_URBANISM',
                      'ACT_PROPRIETATE', 'PLAN_SITUATIE', 'DECLARATIE_CONFORMITATE', 'ALTUL')),
    entry_name    text   NOT NULL,   -- path inside the zip
    content_type  text   NOT NULL,
    size_bytes    bigint NOT NULL CHECK (size_bytes > 0),
    sha256        text   NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Validity data for permits (aviz / certificat de urbanism). Not used for
    -- the identity document, whose number is personal data.
    issuer        text,
    issued_on     date,
    valid_until   date,
    FOREIGN KEY (region) REFERENCES region (code),
    FOREIGN KEY (client_id, region) REFERENCES client (client_id, region) ON DELETE CASCADE,
    CONSTRAINT client_document_entry_uk UNIQUE (client_id, entry_name),
    CONSTRAINT client_document_validity CHECK (valid_until IS NULL OR issued_on IS NULL OR valid_until >= issued_on)
);
CREATE INDEX client_document_type_idx ON client_document (client_id, document_type);

-- =============================================================================
-- Audit trail (append-only)
-- =============================================================================

CREATE TABLE audit_log (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region      text        NOT NULL REFERENCES region (code),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor       text        NOT NULL,   -- user id, or 'system:<component>'
    action      text        NOT NULL,   -- e.g. CLIENT_CREATED, CLIENT_STATUS_CHANGED, FILE_DOWNLOADED
    entity_type text        NOT NULL,
    entity_id   text        NOT NULL,
    trace_id    text,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX audit_log_region_time_idx ON audit_log (region, occurred_at);

CREATE FUNCTION audit_log_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% rejected)', TG_OP;
END $$;
CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_is_append_only();

-- =============================================================================
-- Privileges for the runtime role (defaults give it SELECT/INSERT/UPDATE/DELETE)
-- =============================================================================

REVOKE UPDATE, DELETE ON audit_log FROM filegrid_app;
REVOKE DELETE ON meter_reading_raw, meter_reading FROM filegrid_app;
REVOKE INSERT, UPDATE, DELETE ON region FROM filegrid_app;

DO $$
BEGIN
    -- Flyway creates its history table before running V1, so default
    -- privileges would let the app rewrite migration history.
    IF to_regclass('flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON flyway_schema_history FROM filegrid_app;
    END IF;
END $$;
