# FileGrid PPC

The entry point for all field data at Rețele Electrice România: smart-meter
readings, substation fault records, customer connection documents and drone
inspection videos. Requirements: [`FileGrid PPC.pdf`](FileGrid%20PPC.pdf).

What is implemented so far:

- **Storage layer**: PostgreSQL, MinIO (S3) and vsftpd (FTP), each in its own
  container, plus the full database schema.
- **Authentication**: Keycloak, with realm roles `client` and `admin`.
- **Backend** (Spring Boot 4, Java 25), including the **racordare** flow.
  Clients submit their connection documents; FileGrid zips them, encrypts the zip
  and stores it; an admin reviews the request and accepts or rejects it.
  See [docs/racordare.md](docs/racordare.md).
This repository currently contains the **storage layer**: PostgreSQL, MinIO (S3)
and vsftpd (FTP), each in its own container, plus the database schema, and the
**smart-meter simulator** ([`simulator/`](simulator/README.md)).

## Quick start

Requirements: Docker with Compose v2, `make` and `openssl`. No JDK needed:
builds and tests run in containers.

```sh
make up      # first run creates .env with random secrets, then starts everything
make demo    # optional: walk through a racordare end to end (needs curl + jq)
```

`make up` generates `.env` and starts the storages and Keycloak. It then runs the
init jobs (DB migrations, MinIO buckets and users) and starts the backend once
all of them are healthy. It is idempotent.

| What | Where | Login |
|---|---|---|
| API docs (Swagger UI) | http://localhost:8080/swagger-ui.html | bearer token |
| Login / self-registration | http://localhost:8180/realms/filegrid/account | new accounts get the `client` role |
| Seeded admin | `admin@filegrid.local` | `FILEGRID_ADMIN_PASSWORD` from `.env` |
| Seeded client | `client.demo@filegrid.local` | `FILEGRID_DEMO_CLIENT_PASSWORD` from `.env` |
| Keycloak console | http://localhost:8180/admin | `admin` / `KEYCLOAK_ADMIN_PASSWORD` |

Get a token for scripts. The `filegrid-cli` client allows the password grant
and is meant for development only:

```sh
set -a; . ./.env; set +a
curl -s http://localhost:8180/realms/filegrid/protocol/openid-connect/token \
  -d grant_type=password -d client_id=filegrid-cli \
  -d username=admin@filegrid.local --data-urlencode "password=$FILEGRID_ADMIN_PASSWORD" | jq -r .access_token
```

In `make up` mode only the backend (8080) and Keycloak (8180) are published,
both on 127.0.0.1. **No storage port is reachable from the host.** To run the
backend from your IDE instead:

```sh
make dev                                  # everything except the backend; storages on 127.0.0.1
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run   # needs JDK 25
```

| Service | `make dev` address | Credentials (from `.env`) |
|---|---|---|
| PostgreSQL 18 | `localhost:5432`, db `filegrid`, schema `filegrid` | `filegrid_app` / `FILEGRID_DB_APP_PASSWORD` |
| MinIO S3 API | `http://localhost:9000` | `FILEGRID_S3_ACCESS_KEY` / `FILEGRID_S3_SECRET_KEY` |
| MinIO console | `http://localhost:9001` | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| FTP (passive) | `ftp://localhost:2121`, data ports 21100-21119 | `FTP_USER` / `FTP_PASSWORD` |

Other targets:

- `make test`: unit and integration tests. They run against real PostgreSQL, MinIO and vsftpd via Testcontainers.
- `make ps`, `make logs`, `make psql`, `make migrate`, `make down`.
- `make env`: adds variables that are new in `.env.example` to your `.env`, without touching existing secrets.
- `make clean`: **deletes all data volumes**.
Meter ingest: meters `POST /api/v1/meter-readings` with the shared `X-API-Key`
(`FILEGRID_METER_API_KEY`). The message is stored as received in `meter_reading_raw`
(`202`; a retry of the same `readingId` returns `200` and stores nothing). Every 5 s
(`FILEGRID_METER_PROCESS_INTERVAL`) a job turns pending messages into `meter_reading`
rows, one per interval, flagged `ANOMALY` for negative energy, register rollback,
voltage outside 207–253 V or a consumption spike. Messages with future or misaligned
timestamps are kept in the raw table as `REJECTED`.

Meter simulator: `make seed` registers the simulated meters in the database,
`make sim` starts sending readings to FileGrid on `localhost:8080`. See
[`simulator/README.md`](simulator/README.md) for rate, anomalies and runtime control.

Other targets: `make ps`, `make logs`, `make psql`, `make migrate`, `make down`,
`make clean` (**deletes all data volumes**).

## Layout

```
compose.yaml              all services; only backend + Keycloak publish ports
compose.dev.yaml          overlay: storages on 127.0.0.1, backend runs on the host
.env.example              config template; `make env` turns it into .env
backend/                  Spring Boot API (racordare, storage, encryption, audit)
db/migration/             Flyway migrations
infra/postgres/initdb/    first-boot roles and databases (filegrid, keycloak)
infra/keycloak/realm/     realm import: roles, clients, seeded users
infra/minio/              bucket bootstrap script + app IAM policy
infra/vsftpd/             FTP server image (Alpine + vsftpd)
scripts/                  env generation, end-to-end demo
docs/                     racordare flow, connection documents research
docs/                     design notes (connection documents research)
simulator/                smart-meter simulator (Spring Boot)
db/seed/                  generated meter registry (`make seed`, git-ignored)
```

## Storage decisions

Default storage per category. These defaults live in the `storage_policy`
table (per category and region), so they can be changed without code changes.
The backend reads the policy on every upload. The tests switch the connection
archives to FTP and to the database just by updating that table.

| Category | Default | Why |
|---|---|---|
| Meter readings (1–50 KB, continuous) | PostgreSQL `meter_reading_raw` | Lots of tiny objects. "No loss, no duplicates" is enforced transactionally by unique constraints. |
| Fault records (1–10 MB, storm bursts) | FTP `/files/fault-records` | Simple, cheap. Dispatch reads them right after upload. |
| Connection documents (PII) | S3 `filegrid-connection-documents`, **encrypted** | Versioned bucket. A DB constraint forbids storing them unencrypted. |
| Drone videos (100 MB – 1 GB+) | S3 `filegrid-drone-videos` | Multipart uploads can resume over unstable links, and memory use stays constant. |

## Database schema (`filegrid` schema)

Every table has a `region` column (`MUNTENIA`, `BANAT`, `DOBROGEA`). Composite
foreign keys stop a child row from having a different region than its parent.

| Table | Purpose |
|---|---|
| `region` | Reference list of distribution areas |
| `storage_policy` | Where each file category is written, per region |
| `stored_file` | One row per physical object. Holds backend, location, size, SHA-256 (as sent and as stored), encryption metadata, upload status and trace id. |
| `stored_file_blob` | Bytes of files whose policy is `DATABASE` |
| `meter` | Smart-meter registry (county, locality, coordinates) |
| `meter_reading_raw` | Readings exactly as received, with an idempotency key `(meter_id, message_id)`, a checksum and a processing queue |
| `meter_reading` | Preprocessed readings, one per meter per interval. Unique `(meter_id, interval_start)`, plus a quality/anomaly flag. |
| `fault_record` | Faults (avarii): substation, time, severity, affected customers, and a link to the file |
| `drone_video` | Inspection videos: line, drone, flight time, and a link to the file |
| `client` | Connection requests: `client_id`, region, `status` (PENDING/ACCEPTED/REJECTED), reviewer, submitter (Keycloak id), and a link to the encrypted zip |
| `client_document` | Manifest of the zip: one row per document, with its type, SHA-256 and permit validity |
| `audit_log` | Append-only trail of who did what (enforced by trigger and grants) |

## Security baseline

- **Secrets**: only in `.env`, which is git-ignored and generated randomly. The repo has `.env.example` with placeholders.
- **Authentication**: Keycloak OIDC.
  - The backend validates issuer, signature and audience (`filegrid-api`) of every token.
  - Realm roles map to `ROLE_CLIENT` / `ROLE_ADMIN`.
  - Brute-force protection is on, and passwords need 12+ characters.
- **Encryption at rest**: connection archives are encrypted by FileGrid before they reach any storage. Each file gets its own key, wrapped by a master key that only exists in `.env`. Someone with direct access to the bucket, FTP or database gets ciphertext only.
- **Integrity**: every download is checked against two SHA-256 hashes, one of the stored bytes and one of the original bytes. Corrupted files are flagged `CORRUPTED` and never served. Responses carry `X-Checksum-SHA256` / `Repr-Digest`.
- **PostgreSQL roles**: three roles, plus a separate `keycloak` role for Keycloak's own database.
  - `postgres` is for ops only.
  - `filegrid_migrator` owns the schema and is used only by Flyway.
  - `filegrid_app` is the runtime role, limited to DML. It cannot DELETE meter readings, cannot UPDATE or DELETE the audit log, and cannot touch the migration history.
- **MinIO**: the app user is limited to `filegrid-*` buckets and has no admin rights. Buckets are private and versioned.
- **FTP**: a single allow-listed account, chrooted, no anonymous access, no shell.
- **Observability hooks**:
  - Prometheus metrics on the unpublished port 8081, with per-category and per-backend latency histograms (`filegrid.storage.operation`) and an integrity-failure counter.
  - JSON logs with a trace id. The trace id is also stored on every file and audit row.

## Notes and next steps

- The Postgres init scripts only run on an empty volume. If you started the stack before Keycloak was added, run `make clean && make up` once.
- Not done yet:
  - TLS reverse proxy in front of the backend and Keycloak.
  - Region scoping for admins.
  - Virus scanning of uploads.
  - Retention policy for rejected requests.
  - SMTP in Keycloak (email verification, password reset).
  - Disabling `filegrid-cli` in production.
- Upstream MinIO stopped publishing Docker images in 2025, so this repo uses Chainguard's continuously rebuilt image, pinned by digest.
- Changing a password in `.env` after the first start does not update an existing volume. Either change it inside the service or run `make clean`.
