# FileGrid PPC

The entry point for all field data at Rețele Electrice România: smart-meter
readings, substation fault records, customer connection documents and drone
inspection videos. Requirements: [`FileGrid PPC.pdf`](FileGrid%20PPC.pdf).

This repository currently contains the **storage layer**: PostgreSQL, MinIO (S3)
and vsftpd (FTP), each in its own container, plus the database schema.

## Quick start

Requirements: Docker with Compose v2, `make` and `openssl`.

```sh
make up      # first run creates .env with random secrets, then starts everything
```

That's the whole setup. `make up` generates `.env`, starts the three storages,
waits until they are healthy, then runs the init jobs (DB migrations, MinIO
buckets and users). The command is idempotent.

In `make up` mode, **no storage port is reachable from the host**: only containers
on the `backend` network (i.e. FileGrid itself) can reach them. To run the
backend from your IDE instead, use:

```sh
make dev     # same stack, with ports bound to 127.0.0.1 only
```

| Service | `make dev` address | Credentials (from `.env`) |
|---|---|---|
| PostgreSQL 18 | `localhost:5432`, db `filegrid`, schema `filegrid` | `filegrid_app` / `FILEGRID_DB_APP_PASSWORD` |
| MinIO S3 API | `http://localhost:9000` | `FILEGRID_S3_ACCESS_KEY` / `FILEGRID_S3_SECRET_KEY` |
| MinIO console | `http://localhost:9001` | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| FTP (passive) | `ftp://localhost:2121`, data ports 21100-21119 | `FTP_USER` / `FTP_PASSWORD` |

Other targets: `make ps`, `make logs`, `make psql`, `make migrate`, `make down`,
`make clean` (**deletes all data volumes**).

## Layout

```
compose.yaml              storages + init jobs, no published ports
compose.dev.yaml          overlay: publish ports on 127.0.0.1 for local dev
.env.example              config template; `make env` turns it into .env
db/migration/             Flyway migrations (V1__baseline.sql = full schema)
infra/postgres/initdb/    first-boot role and schema setup
infra/minio/              bucket bootstrap script + app IAM policy
infra/vsftpd/             FTP server image (Alpine + vsftpd)
docs/                     design notes (connection documents research)
```

## Storage decisions

Default storage per category. These defaults live in the `storage_policy`
table (per category and region), so they can be changed without code changes.

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
| `client` | Connection requests: `client_id`, region, `status` (PENDING/ACCEPTED/REJECTED), reviewer, and a link to the encrypted zip |
| `client_document` | Manifest of the zip: one row per document, with its type, SHA-256 and permit validity |
| `audit_log` | Append-only trail of who did what (enforced by trigger and grants) |

The zip is referenced through `client.archive_file_id → stored_file`, not as a
URL. Storages can't be reached from outside, so downloads always go through
FileGrid, which decrypts the zip, checks its hash and writes an audit entry.
[`docs/connection-documents.md`](docs/connection-documents.md) lists what each
connection document must contain.

## Security baseline

- **Secrets**: only in `.env`, which is git-ignored and generated randomly. The repo has `.env.example` with placeholders.
- **PostgreSQL roles**: three roles. `postgres` is for ops only. `filegrid_migrator` owns the schema and is used only by Flyway. `filegrid_app` is the runtime role: DML only. It cannot DELETE meter readings, cannot UPDATE or DELETE the audit log, and cannot touch the migration history.
- **MinIO**: the app user is limited to `filegrid-*` buckets and has no admin rights. Buckets are private and versioned.
- **FTP**: a single allow-listed account, chrooted, no anonymous access, no shell.
- **No plaintext personal data in the database**: names, CNP and ID numbers exist only inside the encrypted archive. `identity_hmac` lets you find an applicant again without storing the CNP.

## Notes

- Upstream MinIO stopped publishing Docker images in 2025, so this repo uses Chainguard's continuously rebuilt image, pinned by digest.
- Changing a password in `.env` after the first start does not update an existing volume. Either change it inside the service or run `make clean`.
- FTP runs without TLS: it is only reachable on the internal network. External traffic will be TLS-terminated in front of FileGrid.
