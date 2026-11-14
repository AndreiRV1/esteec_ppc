# FileGrid API contract

What the backend implements, for anyone coding against it (frontend, tools). The
live, exact schemas are at `/v3/api-docs` and `/swagger-ui.html`. URLs and the
token issuer per run mode are in [`platform.md`](platform.md); the racordare
flow is in [`racordare.md`](racordare.md).

## Conventions

- Base path `/api/v1`. JSON everywhere except file bodies.
- **Auth:** `Authorization: Bearer <Keycloak access token>` (realm `filegrid`).
  Realm roles in `realm_access.roles`: `client`, `employee`, `admin`.
  Meters use `X-API-Key` instead of a token.
- **Errors:** RFC 9457 problem JSON with a stable `code` (and `traceId`), e.g.
  `{"status":409,"detail":"Expected part 2, got 3","code":"part_out_of_order"}`.
- **Timestamps:** ISO-8601 UTC (`2026-11-14T10:00:00Z`).
- **Regions:** `MUNTENIA | BANAT | DOBROGEA`.
- **County:** requests accept the two-letter code (`TM`) or the name (`Timiș`),
  and it must belong to the given region (`county_region_mismatch`). Responses
  always carry the name.
- **Lists:** `{items, page, size, total}`; `page` starts at 0, `size` 1–100 (default 20).
- **Downloads:** the file is checked against its SHA-256 before the first byte is
  sent. A corrupted file is never returned: `500` with code `integrity_check_failed`.
  Responses carry `X-Checksum-SHA256` (hex) and `Repr-Digest`.
- **Storage down:** `503` with code `storage_unavailable`, only for the category
  whose storage is affected. Retry later.

## Who may call what

| Routes | Roles |
|---|---|
| `/me/**` | any logged-in user |
| `/connection-requests/**` | `client`, `employee` |
| `/fault-records/**`, `/drone-videos/**` | `employee`, `admin` |
| `/admin/**` | `admin` |
| `POST /meter-readings` | meters (`X-API-Key`) |

## Me (customer portal)

A customer logs in with their client id as the username (Keycloak lower-cases it,
e.g. `cl-0004211`). The account is linked to one meter.

- `GET /me` → `{username, roles, region, county, locality, meterId}`.
  For users without a customer account (staff) the last four are `null`.
- `GET /me/consumption?from&to&bucket=HOUR|DAY` →
  `{meterId, bucket, points:[{start, kwh, anomalies}]}`.
  Defaults: last 7 days, `HOUR`. `from` inclusive, `to` exclusive. At most 31 days
  for `HOUR`, 366 for `DAY` (`invalid_range`). Day buckets start at local midnight
  (Europe/Bucharest). Buckets with no readings are omitted. `anomalies` is the
  number of flagged readings in the bucket; negative readings are not added to `kwh`.
- `GET /me/consumption/summary` → `{monthToDateKwh, previousMonthKwh, lastReadingAt}`
  (calendar months, local time).
- `GET /me/faults` → faults in the customer's county that are not restored yet:
  `[{id, county, locality, substationCode, occurredAt, restoredAt, severity, cause, affectedCustomers}]`.
- The three customer endpoints return `404` `no_customer_account` for other users.

## Fault records

- `POST /fault-records` — `multipart/form-data`: part `file`, and part `metadata`
  (`Content-Type: application/json`):
  `{region, county, locality?, substationCode, occurredAt, restoredAt?, severity, cause?, affectedCustomers?, description?}`,
  `severity` = `LOW | MEDIUM | HIGH | CRITICAL`. → `201 {id, fileId, sha256}`.
  The file is downloadable as soon as this returns. Not idempotent: a retried
  POST creates a second record.
- `GET /fault-records?region&county&severity&from&to&page&size` (newest first;
  `from`/`to` filter `occurredAt`) → page of
  `{id, region, county, locality, substationCode, occurredAt, restoredAt, severity, cause, affectedCustomers, description, reportedBy, createdAt, file}`
  with `file = {fileId, filename, contentType, sizeBytes, sha256, backend}`.
- `GET /fault-records/{id}` → the same object. `GET /fault-records/{id}/file` → the file.
- `POST /fault-records/{id}/restore` with optional `{restoredAt}` (default now) →
  the updated record. *(Addition to the original contract: without it a fault
  would stay "active" for customers forever.)*

## Drone videos (resumable upload)

1. `POST /drone-videos/uploads`
   `{region, county, lineCode, droneId?, flightStartedAt, durationSeconds?, notes?, filename, contentType, sizeBytes, sha256}`
   → `201 {uploadId, partSizeBytes}`. `sha256` is of the whole file (64 hex chars).
2. `PUT /drone-videos/uploads/{uploadId}/parts/{n}` — raw bytes, `Content-Length`
   required → `204`.
   - Parts are numbered from 1 and **must be sent in order**. Every part except
     the last is exactly `partSizeBytes`.
   - A part the server already has → `204`, no effect (safe to retry).
   - A part ahead of the expected one → `409` `part_out_of_order`; ask for the status.
   - `400` `invalid_part_size`, `part_checksum_mismatch`, `part_interrupted` → send
     the same part again.
   - Optional header `X-Checksum-SHA256` (hex of the part) is verified.
3. `GET /drone-videos/uploads/{uploadId}` →
   `{status, bytesReceived, receivedParts, sizeBytes, partSizeBytes}`.
   To resume: continue with part `receivedParts.length + 1`.
4. `POST /drone-videos/uploads/{uploadId}/complete` → `201 {id, fileId, sha256}`.
   The stored object is read back and compared with the declared `sha256`.
   `409` `upload_incomplete` if parts are missing; `422` `checksum_mismatch` if the
   stored file differs (the upload is discarded, start again). Safe to call twice.

Only the user who started an upload can continue it (others get `404`). A video
appears in lists only after `complete`. An upload that is never completed stays
invisible (there is no clean-up job yet).

- `GET /drone-videos?region&lineCode&from&to&page&size` (newest flight first) → page of
  `{id, region, county, lineCode, droneId, flightStartedAt, durationSeconds, notes, uploadedBy, createdAt, file}`.
- `GET /drone-videos/{id}`; `GET /drone-videos/{id}/file` supports one `Range`
  (`206`). Every request verifies the whole file first, so seeking in a large
  video is slow; download it rather than streaming it in a player.

## Storage policies (admin)

- `GET /admin/storage-policies` →
  `[{category, region, backend, target, encryptAtRest, maxSizeBytes, updatedAt, updatedBy}]`,
  `category` = `FAULT_RECORD | CONNECTION_DOCUMENTS | DRONE_VIDEO`.
- `PUT /admin/storage-policies/{category}/{region}`
  `{backend, target, encryptAtRest, maxSizeBytes}` → the updated policy.
  Applies to the next upload; files already stored stay where they are and stay
  readable. Audited. Rules (`400` `invalid_storage_policy`):

  | Backend | `target` | Limit |
  |---|---|---|
  | `S3` | existing bucket named `filegrid-...` | — |
  | `FTP` | directory under `/files`, e.g. `/files/fault-records` | — |
  | `DATABASE` | must be omitted | `maxSizeBytes` ≤ 104857600 |

  `CONNECTION_DOCUMENTS` must have `encryptAtRest: true`. `DRONE_VIDEO` must have
  `encryptAtRest: false` (resumable parts cannot be encrypted at rest).
  Meter readings are always stored in PostgreSQL and have no policy.

## Connection requests (racordare)

Unchanged; see [`racordare.md`](racordare.md). An `employee` can now submit a
request on an applicant's behalf and sees the requests they submitted. An admin
cannot review a request they submitted themselves (`403` `own_request`).

## Meter readings

`POST /meter-readings` with `X-API-Key`; see [`../simulator/README.md`](../simulator/README.md).
`202` stored, `200` already stored (same `readingId`), `400` malformed, `422`
unknown meter or wrong region.
