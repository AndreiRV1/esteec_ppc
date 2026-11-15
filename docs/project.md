# FileGrid PPC: the whole project

This is the one document to read before working on or presenting FileGrid. It explains what the system
does, how every component is built, how each step of the demo works under the hood, and what is still
open. The requirements are in [`FileGrid PPC.pdf`](../FileGrid%20PPC.pdf); the live script is
[`demo.md`](demo.md).

Contents: [1. What it is](#1-what-filegrid-is) · [2. Architecture](#2-architecture) ·
[3. Repository map](#3-repository-map) · [4. Running it](#4-running-it) ·
[5. Backend](#5-backend) · [6. Storage, integrity, encryption](#6-storage-integrity-and-encryption) ·
[7. Data categories](#7-the-data-categories-end-to-end) · [8. Document autofill (OCR)](#8-document-autofill-ocr-and-video-metadata) ·
[9. Database](#9-database) · [10. Frontend](#10-frontend) · [11. Identity](#11-identity-keycloak) ·
[12. Observability](#12-observability) · [13. Simulator and data generators](#13-simulator-and-data-generators) ·
[14. Kubernetes](#14-kubernetes) · [15. Scripts](#15-scripts-and-make-targets) ·
[16. How each demo step is implemented](#16-how-each-demo-step-is-implemented) · [17. Tests](#17-tests) ·
[18. Known limits and open items](#18-known-limits-and-open-items) · [19. Where to change things](#19-where-to-change-things)

---

## 1. What FileGrid is

Rețele Electrice România (PPC group; Muntenia, Banat, Dobrogea) receives four kinds of field data. Today
they end up in FTP, S3 and databases chosen ad hoc. FileGrid is the **single entry point** for all of it:

| Category | How it enters | Size | Business guarantee (spec table 2.1) | Default storage |
|---|---|---|---|---|
| Smart-meter readings | sent by (simulated) meters to a public API | 1–50 KB | none lost, none doubled | PostgreSQL tables |
| Fault records (*avarii*) | uploaded by dispatch in the UI | 1–10 MB | available to dispatch immediately, even at storm peaks | FTP (vsftpd) |
| Connection documents (*racordare*) | uploaded by applicants or employees | 0.1–10 MB | personal data protected and auditable | S3 (MinIO), encrypted |
| Drone inspection videos | uploaded by field teams | 100 MB – 1 GB+ | transfer survives slow, unstable links | S3 (MinIO) |

For the three file categories, a **storage policy** per category and region decides where and how each file
is written (FTP / S3 / database, encryption, size limit), and an admin can change it at runtime. Every file is
hashed on the way in and verified on the way out. Everything is measured, traced and alerted on.

## 2. Architecture

```
                 Browser / meters / scripts
                            │  HTTPS only (127.0.0.1:8443)
                    ┌───────▼────────┐
                    │  Caddy proxy   │  TLS, routing, security headers, ops-only paths
                    └─┬──┬──┬──┬──┬──┘
      /  (SPA)        │  │  │  │  │ /mailpit (operators only)
   ┌──────────────────┘  │  │  │  └───────────────────┐
   ▼                     ▼  │  ▼                      ▼
 frontend          /api/*   │ /grafana            Mailpit (alert inbox)
 (Angular, nginx)     │     │   │
                      ▼     ▼   ▼
               backend(s)  Keycloak  Grafana ── Prometheus, Loki, Tempo
               Spring Boot   (OIDC)            ▲      ▲       ▲
                │   │   │                      │      │       │
   ┌────────────┘   │   └───────────┐   metrics │  logs│ traces│ (Alloy ships logs)
   ▼                ▼               ▼          │      │       │
 PostgreSQL       MinIO (S3)     vsftpd (FTP)  └──────┴───────┘
 (internal "backend" network: not reachable from the host or the proxy)
```

- **One published port.** Only the proxy binds a host port (`127.0.0.1:8443`). Storages live on the internal
  `backend` network; the proxy is not even on it. Networks in `compose.yaml`: `edge` (proxy, frontend, backend,
  Keycloak, Grafana), `backend` (storages and the services allowed to reach them), `observability`
  (metrics, logs), `telemetry` (only the proxy's entry spans to Tempo).
- **Stateless backends.** Upload progress, policies and processing state live in PostgreSQL, so any number of
  backend replicas can serve any request (`docker compose up -d --scale backend=2`, or the HPAs in `k8s/`).
- **Everything in containers.** Builds and tests run in containers too: Docker, `make` and `openssl` are all a
  clean machine needs.

## 3. Repository map

| Path | What |
|---|---|
| `compose.yaml` | the full stack (16 long-running services + 2 init jobs); only the proxy publishes a port |
| `compose.dev.yaml` | IDE overlay: storages and Keycloak on 127.0.0.1, proxy forwards `/api` to the host's :8080 |
| `Makefile` | every entry point (`make help` lists them) |
| `.env.example` → `.env` | configuration; `scripts/gen-env.sh` fills in random secrets (`.env` is git-ignored) |
| `backend/` | Spring Boot 4 / Java 25 API (`ro.ppc.filegrid.*`), Dockerfile (JRE Alpine + Tesseract) |
| `backend/fault_videos/` | Python generator for fault JSONs and drone MP4s, plus an API uploader / verifier |
| `frontend/` | Angular 22 SPA (standalone components, signals, zoneless), served by nginx |
| `simulator/` | Spring Boot smart-meter fleet simulator (Java 21) |
| `db/migration/` | Flyway SQL: `V1__baseline.sql` (schema), `V2__client_submitter.sql`, `V3__customer_account.sql` |
| `infra/proxy/` | Caddyfile and the placeholder page shown when no frontend is deployed |
| `infra/keycloak/` | realm import (roles, clients, seeded users) and the `filegrid` login theme |
| `infra/postgres/initdb/` | first-boot roles: `filegrid_migrator` (owns schema), `filegrid_app` (DML only), `keycloak` |
| `infra/minio/` | bucket bootstrap and the backend's IAM policy |
| `infra/vsftpd/` | hardened Alpine vsftpd image |
| `infra/degraded/` | image used by `demo-degraded.sh` (netem on the client's interface) |
| `infra/observability/` | Prometheus (+ rules), Alertmanager, Blackbox, Loki, Alloy, Tempo, Grafana (+ 3 dashboards) |
| `k8s/` | Kustomize manifests: backend split per category, HPAs, NetworkPolicies, Ingress |
| `scripts/` | env generation, Keycloak sync, demo acts, demo data generators, client seeding |
| `demo/` | committed demo files: specimen PDFs (+ scanned copies), fault reports, a tagged MP4 |
| `docs/` | `demo.md` (showcase), `api.md` (REST contract), `platform.md` (proxy/observability), `racordare.md` and `connection-documents.md` (connection flow and crypto design), `evidence.md` (measured results), this file |

## 4. Running it

```sh
make up            # .env with fresh secrets (first run), all services, migrations, buckets, realm sync,
                   # backend, seed data if empty, real-time meter simulator. Prints the URLs.
make demo-setup    # demo data: fleet, client logins, 2 days of readings, faults, videos; prints logins
make demo-files    # regenerate the committed demo PDFs and MP4 (only needed if you change them)
make test          # backend unit + integration tests (Testcontainers, in Docker)
cd frontend && npm test          # frontend unit tests (Vitest)
cd frontend && npm run start:mock  # the UI alone with fixture data, no backend
make dev           # everything except the backend; run it from the IDE (cd backend && ./mvnw spring-boot:run)
make down          # stop, keep data      ·  make clean  # stop and DELETE all volumes
```

Logins: `admin@filegrid.local`, `employee.demo@filegrid.local`, `client.demo@filegrid.local`; passwords are
`FILEGRID_ADMIN_PASSWORD`, `FILEGRID_DEMO_EMPLOYEE_PASSWORD`, `FILEGRID_DEMO_CLIENT_PASSWORD` in `.env`.
URLs: app `https://localhost:8443/`, Swagger `/swagger-ui.html`, Grafana `/grafana/` (admin only),
Mailpit `/mailpit/`, Keycloak admin `/auth/admin/`.

How `make up` orders things (see the `start` macro in the `Makefile`): storages and Keycloak first
(`--wait` on health checks) → one-shot jobs `db-migrate` (Flyway) and `minio-init` (buckets, policy) → the
platform (observability, proxy, frontend) → `keycloak-sync.sh` (brings an existing realm up to date) →
backend → `seed-if-empty` → simulator.

## 5. Backend

Spring Boot 4 on Java 25 with virtual threads, JDBC (`JdbcClient`, no ORM), Flyway-managed schema, OAuth2
resource server, Micrometer + OpenTelemetry, springdoc. API on port 8080, actuator/management on 8081 (not
routed by the proxy). Configuration is in `backend/src/main/resources/application.yaml`, bound to
`config/FileGridProperties.java`; every secret comes from the environment.

### Packages

| Package | Classes and what they do |
|---|---|
| `common` | `ApiException` + `ApiExceptionHandler`: every error is an RFC 9457 problem document with a stable `code` the UI switches on. `TraceIds`: current W3C trace id, stored with every write. `TraceIdHeaderFilter`: returns `X-Trace-Id` on every response (the "ID operație" in the UI). `County`/`Region`: the served counties and their region (the region is derived from the county, never trusted from input). `UuidV7`: time-ordered ids. `Users`: how a caller is named in records. |
| `config` | `SecurityConfig`: stateless JWT, URL → role rules (below). `KeycloakRealmRoleConverter`: `realm_access.roles` → `ROLE_*`. `CategoryObservationConvention`: tags every HTTP metric with its data category, which is what makes "latency per category" possible. `FileGridProperties`, `OpenApiConfig`. |
| `storage` | The storage layer (section 6): `StoredFileService` (write/read with integrity), `ObjectStore` with `FtpObjectStore`, `S3ObjectStore`, `DatabaseObjectStore`; `StoragePolicy*` (policy table, admin API); `ResumableUploadService` (parts); `Downloads` (streaming responses with checksum headers and Range); `TempFiles`, `VerifiedContent`, `StoredFile*`, exceptions. |
| `crypto` | `EnvelopeEncryption`: per-file data key (Tink AES-256-GCM-HKDF streaming), wrapped by a master key from the environment; file id bound as associated data. |
| `audit` | `AuditLog`: append-only `audit_log` rows written inside the business transaction. |
| `meter` | `MeterReadingController` (ingest), `MeterReadingProcessor` (raw → readings), `MeterReadingRepository`, `MeterBacklogMetrics` (backlog gauges), `MeterProperties`, `MeterConfig`. |
| `fault` | `FaultRecordController` (upload, search, download, restore), `FaultRecordRepository`, `FaultRecords`, `Severity`, `Metadata`. |
| `drone` | `DroneVideoController` (start upload, PUT parts, complete, search, download), `DroneVideoRepository`, `DroneVideos`. |
| `connection` | Racordare: `ConnectionRequestService` (submit, review), `ConnectionRequestController` (applicant), `AdminConnectionRequestController` (review queue), `PublicRegistrationController` (no account yet), `KeycloakAccounts` (creates applicant logins), `IdentityNumbers` (CNP/CUI checksums), `IdentityHasher` (HMAC of CNP/CUI), `DocumentFormat` (magic-byte detection), `ConnectionRequestForm`/`Records`/`Repository`/`Types`. |
| `account` | Customer portal: `MeController` (`/api/v1/me/**`: profile, consumption series and summary, faults in my area), `AccountRepository` (local-time days/months, like the bill). |
| `stats` | `StatisticsController` + `StatisticsRepository` (spec §5 page), `PrometheusQueries` (instant queries across all replicas), `AlertsController` (firing alerts from Alertmanager, mapped to table 2.1). |
| `extraction` | Form autofill (section 8): `DocumentExtractionController`, `DocumentText` (PDF text layer or Tesseract OCR), `DocumentFields` (label → field parsing). |

### Who may call what (`SecurityConfig`)

| Path | Access |
|---|---|
| `/actuator/health/**`, `/actuator/prometheus`, Swagger | public (management port is not exposed by the proxy) |
| `POST /api/v1/meter-readings` | public, but the controller requires `X-API-Key` (meters have no user account) |
| `POST /api/v1/public/connection-requests` | public: registration creates the login |
| `POST /api/v1/public/document-extraction` | public: autofill for the registration form; reads, stores nothing |
| `/api/v1/connection-requests/**` | `client`, `employee` |
| `/api/v1/fault-records/**`, `/api/v1/drone-videos/**` | `employee`, `admin` |
| `/api/v1/admin/connection-requests/**` | `employee`, `admin` (review queue; nobody decides a request they filed) |
| `/api/v1/admin/**` (policies, statistics, alerts) | `admin` |
| `/api/v1/me/**` | any logged-in user |

`RoleMatrixTest` checks this whole table against the running application.

## 6. Storage, integrity and encryption

### The policy

`storage_policy` has one row per (category, region): backend (`FTP`/`S3`/`DATABASE`), target (FTP directory or
S3 bucket; none for the database), `encrypt_at_rest`, `max_size_bytes`. It is **read on every upload**, so a
change applies to the next file; existing files keep their recorded location and stay readable. Database
constraints enforce the rules that must never be broken: connection documents are always encrypted, the
database backend is limited to 100 MB. Changes go through `StoragePolicyAdminController` (validated: bucket
names, FTP paths without `.`/`..`), are audited, and show in the admin UI's *Politici de stocare*.

### Write path (`StoredFileService.store`)

1. Look up the policy, pick the `ObjectStore`, create a UUIDv7 file id and a location.
2. **Spool** the upload to a private temp file while computing two SHA-256s: of the plaintext (what the user
   sent) and of the stored bytes (after encryption, if the policy encrypts). The size limit is enforced while
   streaming. Memory stays constant regardless of size.
3. Transaction 1: insert `stored_file` as `UPLOADING` (trace span *db record file*).
4. Write to the backend (span *storage put*, tagged with file id, location and bytes; timed per category and
   backend for the dashboards). FTP writes `<path>.part` and renames it when complete, so a reader never sees half a file.
5. Transaction 2: mark `STORED` with both checksums **and** run the category's own insert (fault row, client
   row, ...) in the same transaction (span *db confirm file*). The file and its metadata appear together or not at all.
6. **Fallback:** if the policy's storage cannot be reached (`StorageUnavailableException`), the file is written to
   the stand-in storage instead, FTP ↔ S3, at the category's default directory or bucket, with the same encryption
   and size limit. The record is moved there and `stored_file.fallback_from` keeps the storage the policy asked for;
   the upload response carries `backend` and `fallbackFrom`, and the UI says so. Video uploads move at start, before
   any part is stored. The database has no stand-in.
7. On any other failure, or if the stand-in is down too: delete the orphaned object, mark the record `FAILED`,
   rethrow (`503 storage_unavailable` for that category only).

### Read path (`retrieveVerified`)

Fetch into a temp file while hashing → compare with the stored checksum → decrypt (the authenticated encryption
fails on any tampering) while hashing → compare with the original checksum. Any mismatch, a missing object or a
decryption failure marks the file `CORRUPTED`, increments the integrity metric (which fires
`FileIntegrityFailure`) and returns `500 integrity_check_failed`. **Unverified bytes are never sent.** Verified
content is streamed by `Downloads` with `Repr-Digest: sha-256=…` (RFC 9530) and `X-Checksum-SHA256`, single
`Range` support, then the temp file is deleted. The browser recomputes the SHA-256 before saving
(`core/sha256.ts`, `downloadAndSave` in `core/api.ts`): integrity is proven end to end.

Life cycle: `UPLOADING → STORED | FAILED`, `STORED → CORRUPTED`.

### Large files in parts (`ResumableUploadService`)

`POST /drone-videos/uploads` announces size and SHA-256 and returns an upload id and part size (8 MB). Parts are
`PUT` in order; each is spooled and written straight to the backend (S3 multipart / FTP append / database), and
`bytes_received` is updated in PostgreSQL. A client that lost its connection asks for the status and continues
with the next part, from any backend instance. `complete` reads the stored object back, compares the SHA-256
with the announced one, and only then marks it `STORED`. Memory does not grow with file size (measured: 353 MiB
before, 392 MiB during a 1 GB upload).

### Encryption and personal data

- **Envelope encryption** (`EnvelopeEncryption`): each file gets its own data key; content is encrypted in 1 MB
  authenticated segments (Tink streaming AEAD), so size doesn't matter. The data key is wrapped by the master
  key (`FILEGRID_MASTER_KEY…` in `.env`) and stored next to the file record. Storages and the database only ever
  hold ciphertext and the wrapped key. Deleting the wrapped key crypto-shreds the file (GDPR erasure).
- **No CNP in the database**: `IdentityHasher` stores HMAC-SHA256(CNP/CUI, server secret), enough to find a
  returning applicant without storing the identifier.
- **Audit**: downloads of archives and documents, decisions, policy changes, uploads are written to `audit_log`
  in the same transaction; a database trigger blocks UPDATE, DELETE and TRUNCATE on it.
- Design details: [`connection-documents.md`](connection-documents.md).

## 7. The data categories end to end

### Meter readings

- **Ingest** (`MeterReadingController`): checks `X-API-Key` first, then reads at most 1 MB of body. Extracts only
  meter, region, message id and period, and inserts the raw JSON into `meter_reading_raw`. The insert is
  idempotent on (meter, reading id): a retry returns `200` instead of `202` and stores nothing. Unknown meters → 422.
- **Processing** (`MeterReadingProcessor`, scheduled): locks a batch of `PENDING` rows with
  `FOR UPDATE SKIP LOCKED` (safe on any number of instances), validates every value (aligned timestamps, no future
  times, values that fit the columns), writes one `meter_reading` row per interval and flags anomalies (negative
  energy, register rollback, voltage outside EN 50160, consumption spikes against the meter's baseline) without
  dropping them. Untrustworthy messages become `REJECTED`, with the raw row kept.
- **Proof against doubles**: a unique key on (meter, interval_start) plus the idempotent raw insert.
- `MeterBacklogMetrics` exposes the backlog size and age, which the alerts watch.

### Fault records

`POST /fault-records` (multipart: metadata JSON + file) → validated (county decides region) → stored per policy
with the `fault_record` row in the same transaction → `FAULT_RECORD_UPLOADED` audit. Search by region, county,
severity and time; download is verified; `POST /{id}/restore` marks supply restored (once, never in the future).
Active faults show to customers of that area (`/me/faults`) as a banner.

### Connection requests (*racordare*)

- **Submit** (`ConnectionRequestService`): form plus three documents (cerere, act de identitate, aviz or
  certificat de urbanism). Validates CNP/CUI checksums, permit dates and formats (magic bytes, ≤ 10 MB each),
  zips them with a `manifest.json` (SHA-256 per document), encrypts and stores the archive per policy, and
  creates the `client` row as `PENDING` with `client_document` rows. All or nothing.
- **Registration** (`PublicRegistrationController`): the same, without an account. FileGrid creates a Keycloak
  user `cl-XXXXXXXX` (via `KeycloakAccounts`, a service account allowed only to manage users) and returns the
  password once.
- **Review** (`AdminConnectionRequestController`): queue and filters, a decrypted view of the request, download of a
  single document or the whole archive (verified, audited), accept or reject (a reason is required, and an admin can't
  decide their own request). Accepting links the applicant's account to a meter (`customer_account`, V3), so the
  customer portal works.
- Flow and states: [`racordare.md`](racordare.md).

### Drone videos

Resumable upload as in section 6, metadata (region, county, line code, drone, flight start, duration, notes) in
`drone_video`; search and verified download. Over 1 GB works at constant memory.

### Customer portal

`/api/v1/me`: profile, the meter linked to the account, consumption per hour/day (local time), month-to-date
register, anomalies, active faults in the area. Customers can only ever see their own meter.

### Statistics (spec §5) and alerts

`StatisticsController` answers the three §5 questions with measured data only (`null` if not measured yet):
which storage fits each category (latency and error rate per category × backend from Prometheus, plus a derived
recommendation), peak day vs normal day (from the database, last 30 days, or by hour with less history), and
what the data says about the grid per region (meters, anomaly rate, faults, hotspots). `AlertsController`
shows what Alertmanager is firing, labelled with the table 2.1 requirement at risk.

## 8. Document autofill (OCR) and video metadata

Forms fill themselves from what the user uploads; the user only checks the result.

- **Endpoint**: `POST /api/v1/public/document-extraction?kind=CONNECTION|FAULT`, multipart `file` (PDF, JPEG or
  PNG, magic bytes checked, ≤ 10 MB). Returns `{source: TEXT|OCR|NONE, fields: {"applicant.fullName": …}}`,
  where the keys are the form's field paths. Public because `/register` has no account. It **stores, logs and
  audits nothing**; the real submit still goes through the normal, validated endpoints.
- **Reading** (`DocumentText`): PDFBox extracts the text layer in reading order, skipping rotated glyphs, so the
  diagonal "SPECIMEN" watermark doesn't split lines. With fewer than 40 characters (a scan), the first 2 pages are
  rendered at 300 dpi and read by **Tesseract 5** (`ron+eng`, `--psm 6`, which keeps each table row on one line).
  Photos go straight to Tesseract. Limits: 2 OCR jobs at once (others get `503 ocr_busy`), 30 s per page,
  temp files deleted. Without Tesseract (backend from the IDE) it returns `NONE` and the form is filled by hand.
- **Parsing** (`DocumentFields`): forms print "label  value" per line, so a field is the rest of the first line
  starting with one of its labels, matched without diacritics or case (OCR drops them). Values are normalised:
  dates to ISO, "9,5 kW" → 9.5, "1.240" → 1240, county names to the official spelling (and the county decides the
  region), enum labels to codes (*Ridicată* → HIGH, *Spor de putere* → SPOR_PUTERE). Typical OCR slips are
  repaired (*lon* → *Ion* in names, `Q` → `@` in e-mails). Anything unreadable is left out, never guessed.
- **Frontend**: the connection form puts *Documente* first. The cerere and the aviz are read, but not the ID card,
  whose dates would land in the permit. Fields are filled only if empty, still at their default, or filled by an
  earlier document, so **what the user typed is never overwritten**. The fault form reads PDFs and photos (and,
  as before, FileGrid JSON exports).
- **Video metadata** (`frontend/src/app/core/mp4-metadata.ts`): reads the MP4 in the browser without loading
  it. It walks top-level boxes with `Blob.slice`, and parses `moov/mvhd` (creation time, duration) and the comment
  tag `region=…;county=…;line_code=…;drone_id=…;flight_started_at=…` that the drones (and the generator) write.
  The filename pattern is a fallback.
- Tests: `DocumentFieldsTest` (the committed demo PDFs and real Tesseract output), `mp4-metadata.spec.ts`.

## 9. Database

PostgreSQL 18, schema `filegrid`, migrations in `db/migration` run by the `db-migrate` job as
`filegrid_migrator`. The backend connects as `filegrid_app` (DML only); triggers keep `audit_log` append-only.

| Table | Holds |
|---|---|
| `region` | MUNTENIA, BANAT, DOBROGEA |
| `storage_policy` | per category × region: backend, target, encryption, size limit (constraints enforce the rules) |
| `stored_file` | every file: category, region, backend, location, checksums (plain and stored), wrapped key, status, upload progress, trace id |
| `stored_file_blob` | content of files on the DATABASE backend |
| `meter`, `meter_reading_raw`, `meter_reading` | fleet; raw messages (idempotent, PENDING/PROCESSED/REJECTED); one row per meter interval with quality/anomaly |
| `fault_record`, `drone_video` | category metadata, each pointing at its `stored_file` |
| `client`, `client_document` | connection requests (status, HMAC of CNP/CUI, archive file, submitter) and the documents inside the archive |
| `customer_account` (V3) | links a Keycloak login to a meter |
| `audit_log` | append-only trail with actor, action, entity, trace id, details |

## 10. Frontend

Angular 22, standalone components, signals, zoneless change detection, template-driven forms, no UI library
(own CSS tokens in `src/styles.css`; Barlow and IBM Plex Mono fonts). Romanian UI. Built by `frontend/Dockerfile`
and served by nginx behind the proxy.

- `app.routes.ts`: each area lazy-loaded and guarded by role (`core/guards.ts`); `/` sends users to their area;
  `/register` and the legal pages are public. Old Romanian URLs redirect.
- `core/`: `auth.ts` (keycloak-js, roles, token refresh), `api.ts` (every backend call, error →
  readable Romanian message, verified downloads, the `Loader` helper), `chunked-upload.ts` (resumable parts with
  retry/backoff and pause, progress persisted in `localStorage`), `sha256.ts` (hashing in slices),
  `mp4-metadata.ts`, `format.ts` (labels, counties, dates), `models.ts`, `config.ts`, `operations.ts` (last
  trace id), `mock-backend.ts` (fixture backend for `npm run start:mock`).
- Pages: `client/client-page.ts` (portal: meter, consumption chart, fault banner, own requests),
  `employee/connection-form-page.ts` (racordare form, also `/register`), `employee/faults-page.ts`,
  `employee/videos-page.ts`, `admin/requests-page.ts` + `request-detail-page.ts`, `admin/policies-page.ts`,
  `admin/statistics-page.ts`, `admin/monitoring-page.ts` (alerts, trace lookup, embedded Grafana).
- `ui/`: `consumption-chart.ts` (SVG chart), `status.ts` (loading/error states), `operation-id.ts` (shows
  `X-Trace-Id` with a link to Grafana), legal and no-access pages.

## 11. Identity (Keycloak)

Realm `filegrid` (`infra/keycloak/realm/filegrid-realm.json`), behind the proxy at `/auth`. Roles `client`,
`employee`, `admin`. Clients: `filegrid-web` (SPA, PKCE), `filegrid-cli` (scripts, password grant),
`filegrid-backend` (confidential; service account that may only manage users, for registration),
`filegrid-grafana` (Grafana SSO, admins only). Seeded users are listed in section 4. The `filegrid` login theme
turns "Register" into "Request a connection", which leads to the app's `/register`. The realm import only runs on
first boot; `scripts/keycloak-sync.sh` brings existing realms up to date. Tokens carry the public issuer, and the
backend fetches keys over the internal network.

## 12. Observability

- **Metrics**: every backend replica exposes `/actuator/prometheus`. Prometheus finds replicas by DNS and scrapes
  each one. HTTP metrics carry a `category` tag, storage calls are timed per category × backend × operation, and
  there are meter backlog gauges and integrity counters. Blackbox probes FTP, S3 and PostgreSQL directly.
- **Alerts** (`infra/observability/prometheus/rules/filegrid-alerts.yml`): 16 rules, each labelled with the
  table 2.1 requirement it protects. MeterReadingsNotProcessed, MeterBacklogGrowing, MeterIngestFailing,
  MeterReadingsRejected, MeterIngestSilent, FaultRecordsSlow, FaultRecordsFailing, ConnectionDocumentsFailing,
  ConnectionDocumentsAccessDenied, DroneVideoUploadsFailing, FileIntegrityFailure, StorageBackendDown,
  StorageOperationsFailing, StorageOperationsSlow, BackendDown, BackendInstanceDown. Alertmanager e-mails them in
  Romanian to the on-call address (Mailpit locally) and sends the "Rezolvat" message when they clear; the admin UI
  shows them under *Monitorizare*.
- **Logs**: ECS JSON with `trace_id`, shipped by Alloy to Loki; from a log line you jump to its trace.
- **Traces**: OpenTelemetry → Tempo, starting at the proxy: proxy → backend → *db record file* → *storage put*
  → *db confirm file*. The trace id is the `X-Trace-Id` header shown in the UI as "ID operație".
- **Grafana** (admin SSO): `filegrid-overview` (throughput, error rate, p50/p95/p99 per category),
  `filegrid-storage` (the same per storage backend), `filegrid-meters` (ingest, duplicates, rejections, backlog,
  anomalies). Details: [`platform.md`](platform.md).

## 13. Simulator and data generators

- **Meter simulator** (`simulator/`, Spring Boot): generates a fleet (`FleetGenerator`, default 2000 meters
  across the three regions), produces 15-minute readings with load profiles (`LoadProfile`), injects anomalies
  (`AnomalyInjector`) and network misbehaviour (resends, reordering, late messages), and sends them through the
  proxy with the API key (`HttpTransport`; a ledger tracks outcomes). Profiles: default (real time), `peak` (the last
  24 h at 10x), `clean` (no anomalies), `seed` (writes the fleet for `make seed`). Always on after `make up`;
  `make sim SIM_ARGS=…` runs an extra one. See [`simulator/README.md`](../simulator/README.md).
- **Fault/video generator** (`backend/fault_videos/fault_videos.py`): `generate` writes regional fault JSONs
  (1–10 MB, oscillogram samples, relay data) and drone MP4s (real H.264 with a HUD overlay if ffmpeg is present,
  otherwise a structurally valid synthetic container). Every MP4 carries the metadata the upload form reads.
  `ingest` uploads through the API, `verify` downloads and compares SHA-256. Profiles `normal` and `storm` (10x),
  plus `--large-video` (> 1 GB).
- **Demo files**: `scripts/make-demo-documents.py` renders the specimen PDFs from HTML with headless Chromium and
  makes the image-only `-scanata` copies (rotated, blurred, noisy) for OCR; `scripts/make-demo-video.sh` makes the
  tagged MP4. Both run via `make demo-files`.

## 14. Kubernetes

`k8s/` (Kustomize, namespace `filegrid`): the same image runs as separate Deployments per data category,
`backend-meters`, `backend-faults`, `backend-videos` and `backend-general`, plus a dedicated `meter-processor`. Each
has its own HorizontalPodAutoscaler and PodDisruptionBudget, so a fault storm scales `backend-faults` alone.
The Ingress routes API paths to the right Deployment. Storages run as StatefulSets with NetworkPolicies allowing
only the backends (and the init jobs) to reach them. Observability and Keycloak have their own manifests.
Secrets are created from `.env` by `k8s/create-secrets.sh`, never committed. Guide and validation:
[`k8s/README.md`](../k8s/README.md).

## 15. Scripts and make targets

| Script / target | What it does |
|---|---|
| `scripts/gen-env.sh` (`make env`) | creates `.env` from `.env.example` with random secrets; later runs only add new variables |
| `scripts/keycloak-sync.sh` | idempotently adds the registration client and permissions to an existing realm |
| `scripts/seed-clients.py` (`make seed-clients`) | creates client logins linked to meters (writes `db/seed/clients.csv`, git-ignored) |
| `scripts/demo-setup.sh` (`make demo-setup`) | fleet, logins, two days of history (simulated fast), faults and videos via the API |
| `scripts/demo-racordare.sh` (`make demo`) | end-to-end racordare through the proxy with curl |
| `scripts/demo-storm.sh` (`make demo-storm`) | 300 fault files by 8 workers while probing the other categories' latency (`REPLICAS=2` scales) |
| `scripts/demo-degraded.sh` (`make demo-degraded`) | uploads from a container with netem: 100 ms, 1 % loss, a 20 s outage; resumes, then verifies |
| `scripts/demo-corruption.sh` (`make demo-corruption`) | uploads, downloads OK, alters the file on FTP directly, download refused, file CORRUPTED, alert |
| `scripts/demo-security.sh` (`make demo-security`) | published ports, 401 without a token, ciphertext in storage, HMAC in the DB, no secrets in Git |
| `scripts/make-demo-documents.py`, `make-demo-video.sh` (`make demo-files`) | the committed demo files |
| `make gen-data` / `gen-storm` / `gen-large-video` / `seed-data` / `verify-data` / `data-status` | generator and uploader |
| `make test`, `psql`, `migrate`, `logs`, `ps`, `down`, `clean` | the usual |

## 16. How each demo step is implemented

The acts follow [`demo.md`](demo.md). For each one: what the audience sees, and what makes it work.

| Act | What is shown | How it is implemented |
|---|---|---|
| **0** One command | `make up`, ~16 services, one TLS port, code in Git | `Makefile` `up` target (ordering in section 4), `gen-env.sh` secrets, health-checked `--wait`, only `proxy` has `ports:` in `compose.yaml`. |
| **1** Registration with autofill | scanned cerere and aviz fill the form; a login is issued once | Section 8 (`DocumentExtractionController` → `DocumentText` PDFBox/Tesseract → `DocumentFields`; `connection-form-page.ts` `pick`/`apply`). Submit: `PublicRegistrationController` → `ConnectionRequestService` (validate, zip with manifest, envelope-encrypt, store per policy, `client` row, audit) → `KeycloakAccounts` creates `cl-…`. |
| **2** Review, download, approve, audit trail | queue, document and archive downloads with checksum, decision, audit rows | `AdminConnectionRequestController`; `StoredFileService.retrieveVerified` (two checksums + AEAD) → `Downloads` (`Repr-Digest`); browser re-hash in `core/sha256.ts`; decision rules in the service; `AuditLog` rows in the same transaction; append-only triggers on `audit_log`. |
| **3** Customer portal | meter, month-to-date, chart, anomalies | Accepting runs `AccountRepository.provisionFromAcceptedRequest` (meter in the applicant's locality); `MeController` series/summary in local time; `ui/consumption-chart.ts`. |
| **4** Meters, none lost or doubled; peak volume | Grafana *Contoare*, SQL with 0 doubles, `make sim … peak` | Idempotent raw insert + `SKIP LOCKED` processor + unique (meter, interval) key (section 7); `MeterBacklogMetrics`; simulator `peak` profile; dashboard `filegrid-meters.json`. |
| **5** Fault from a scanned report; banner; search, download, resolve | OCR fills all fields; client sees the banner; restore clears it | `faults-page.ts` `fillFromDocument` → extraction endpoint (`kind=FAULT`); `FaultRecordController` upload/search/download/restore; `/me/faults` for the banner; the restore guard (`restored_at IS NULL`, not in the future). |
| **6a** Video: metadata autofill, resumable upload, search and download | fields from the MP4, parts, pause/resume, read-back check | `mp4-metadata.ts`; `chunked-upload.ts` (8 MB parts, backoff, `localStorage`); `ResumableUploadService` (progress in `stored_file`, read-back SHA-256 on complete); verified download. |
| **6b** > 1 GB at constant memory | `docker stats` barely moves | Streaming spool → backend per part, never buffering a whole file; S3 multipart; async streaming download with no timeout. |
| **7** Policy without code; the database as storage | switch a category to *Bază de date*, upload, SQL shows `DATABASE` | `StoragePolicyAdminController` (validated, audited); policy read on every upload (`StoragePolicyRepository`); `DatabaseObjectStore` → `stored_file_blob`; DB constraints (encryption for documents, 100 MB cap). |
| **8** 10x storm in one category | other categories' p50/p95 unchanged; `REPLICAS=2` | Stateless backends behind Caddy DNS load balancing; per-category metrics tag; `demo-storm.sh` probes; in k8s, separate Deployments + HPA per category. |
| **9a** Corruption detected | download refused, file CORRUPTED, alert e-mail | `retrieveVerified` checksum mismatch → `CORRUPTED` + integrity counter → `FileIntegrityFailure` → Alertmanager → Mailpit; `demo-corruption.sh` edits the file on FTP directly. |
| **9b** Degraded link | upload stalls, resumes, verified | `demo-degraded.sh` + `infra/degraded` (netem); generator uploader retries with backoff and asks the server for the confirmed offset; same `ResumableUploadService`. |
| **9c** A storage goes down | uploads for FTP land on S3 and say so; *Statistici* shows FTP down; alert and "Rezolvat" | `StoredFileService.putWithFallback` (FTP ↔ S3, `fallback_from`, V4 migration); `ResumableUploadService.begin` for videos; `storageReachable` from `probe_success` on the statistics page; Blackbox probe → `StorageBackendDown`; confirmed files untouched. |
| **10** Security | one port, 401, ciphertext in MinIO, HMAC in DB, no secrets in Git | Networks in `compose.yaml`; `SecurityConfig`; `EnvelopeEncryption`; `IdentityHasher`; `.gitignore` + `gen-env.sh`; checks in `demo-security.sh`. |
| **11** Trace one operation; per-category and per-storage metrics; alerts | paste the ID operație → one trace to storage | `TraceIdHeaderFilter` (`X-Trace-Id`), OpenTelemetry spans in `StoredFileService`, Caddy entry spans → Tempo; `CategoryObservationConvention`; dashboards; `AlertsController` + 16 rules. |
| **12** Statistics with interpretation | storage per category, peak vs normal, grid insights | `StatisticsController` + `PrometheusQueries` + `StatisticsRepository`; `admin/statistics-page.ts`. Values never invented (`null` until measured). |

## 17. Tests

- **Backend** (`make test`; JUnit 5, Spring Boot test slices, Testcontainers for PostgreSQL, MinIO and FTP):
  `FileIngestFlowTest` (store/retrieve on each backend, corruption, outage), `MeterReadingFlowTest` (idempotence,
  anomalies, rejections, values too large for the columns), `ConnectionRequestFlowTest` (submit, review,
  decisions, audit), `CustomerPortalTest`, `RoleMatrixTest` (every route × role), `EnvelopeEncryptionTest`,
  `IdentityNumbersTest`, `DocumentFieldsTest` (autofill on the demo PDFs and OCR output). 48 tests, all passing.
- **Frontend** (`npm test`; Vitest + jsdom): `api.spec.ts`, `chunked-upload.spec.ts`, `guards.spec.ts`,
  `mp4-metadata.spec.ts`. 24 tests, all passing.
- **End to end**: the `make demo*` scripts double as system tests against the running stack; measured results
  are in [`evidence.md`](evidence.md).

## 18. Known limits and open items

Found in the code reviews and not fixed yet (each needs a decision or infrastructure, not just a code change):

- **Rate limits are per replica**: `PublicRateLimitFilter` keeps its counters in memory (10 registrations per
  hour, 30 autofill reads per 10 minutes, per IP), so N replicas allow N times that. Move it to the proxy or a
  shared store before running the public endpoints on many replicas.
- **Keycloak's own sign-up action is still enabled** (`registrationAllowed: true`): the login page's
  "Request a connection" link depends on it. Fix: keep the link in a custom theme and disable registration, or
  bind the registration flow to a deny step.
- **k8s Ingress** exposes `/mailpit` and the Keycloak admin paths without the IP restriction Caddy applies.
- **k8s sizing**: Hikari pool 5 per pod and `max_connections=300` leave no headroom at full HPA scale, and
  Postgres memory is still 1 Gi. Untested under k8s peak load.
- **Abandoned video uploads** are never aborted or swept (S3 multipart parts, FTP `.part` files remain).
- **Employee-filed requests** don't create an applicant login on approval (only self-filed ones do).
- A failing audit insert while opening a document can leave a decrypted temp file behind.
- **OCR** reads the layouts of the demo forms well (label and value on one line); unusual layouts, handwriting or
  poor scans fill fewer fields. The user always reviews before submitting.

## 19. Where to change things

| To change | Edit |
|---|---|
| A REST endpoint | the controller in its package; contract in `docs/api.md`; roles in `SecurityConfig` + `RoleMatrixTest` |
| The schema | a new `db/migration/V4__….sql` (never edit applied migrations) |
| Default storage per category | `storage_policy` seed in `V1__baseline.sql` for new installs; the admin UI at runtime |
| What the autofill recognises | labels and parsers in `extraction/DocumentFields.java` (+ `DocumentFieldsTest`) |
| Alert rules / dashboards | `infra/observability/prometheus/rules/`, `infra/observability/grafana/dashboards/` |
| UI texts and pages | `frontend/src/app/**` (Romanian strings live in the templates) |
| Secrets or new config | `.env.example` (`__GENERATE__` placeholders), `application.yaml`, `compose.yaml`, `k8s/create-secrets.sh` |
| Demo files | `scripts/make-demo-documents.py`, `scripts/make-demo-video.sh`, then `make demo-files` |
