# Racordare: connection requests

How a client gets connected, end to end. For the content of each document see
[connection-documents.md](connection-documents.md).

## Flow

```
client (Keycloak role `client`)          FileGrid backend                         storages
───────────────────────────────          ────────────────                         ────────
register / log in at Keycloak
POST /api/v1/connection-requests  ──▶  1. validate form, CNP/CUI checksum,
  request (JSON) + 3 files                 permit still valid, files are
                                           PDF/JPEG/PNG (magic bytes), ≤ 10 MB
                                        2. region := county's region
                                        3. stream into a zip: 01-cerere…,
                                           02-act-identitate…, 03-aviz…,
                                           manifest.json (full form incl. PII)
                                        4. encrypt the zip while writing
                                           (per-file key, wrapped by master key)
                                        5. stored_file row = UPLOADING ─────────▶ upload ciphertext
                                                                                   (S3 / FTP / DB,
                                                                                    per storage_policy)
                                        6. one transaction: stored_file = STORED,
                                           client row (PENDING), client_document
                                           rows, audit_log entry
◀── 201 {clientId, status: PENDING}
                                        any failure: object deleted,
                                        stored_file = FAILED, no client row

admin (Keycloak role `admin`)
GET  /api/v1/admin/connection-requests?status=PENDING   queue, no personal data
GET  /api/v1/admin/connection-requests/{id}       ──▶  fetch → check stored SHA-256 →
                                                         decrypt → check SHA-256 →
                                                         read manifest (audited)
GET  …/{id}/archive, …/{id}/documents/{docId}     ──▶  same verification, streamed with
                                                         X-Checksum-SHA256 + Repr-Digest (audited)
POST …/{id}/decision {decision, reason}           ──▶  PENDING → ACCEPTED / REJECTED (audited)

client: GET /api/v1/connection-requests/{id} → sees the new status
```

## Rules enforced

| Rule | Where |
|---|---|
| Personal data never stored in clear: name, CNP, CI, addresses, phone and email live only in the encrypted zip | service + `stored_file_pii_encrypted`, `storage_policy_pii_encrypted` constraints |
| Region comes from the county, never from the client | `County` enum |
| Rejecting requires a reason | API + `client_rejection_has_reason` constraint |
| Only PENDING requests can be decided; two admins cannot both decide | conditional `UPDATE … WHERE status = 'PENDING'` → 409 |
| An admin cannot review their own request | service → 403 `own_request` |
| Clients only see their own requests (others → 404, not 403) | service |
| Corrupted or missing objects are never served; the file is marked `CORRUPTED` | `StoredFileService.retrieveVerified` |
| Every submission, view, download and decision is audited | `audit_log` (append-only, enforced in the DB) |

## Encryption

- Each archive has its own data key: a Tink AES-256-GCM-HKDF streaming key with
  1 MB authenticated segments, so memory use stays constant whatever the file size.
- The data key is wrapped with the master key (`FILEGRID_MASTER_KEY_V1`, AES-256-GCM)
  and stored in `stored_file.wrapped_dek`. The master key exists only in `.env`.
- The file id is bound as associated data, so a ciphertext cannot be swapped
  onto another record.
- **Rotation:** add `kek-v2` under `filegrid.crypto.master-keys` and point
  `FILEGRID_MASTER_KEY_ID` at it. Old files stay readable.
- **GDPR erasure:** delete the row's `wrapped_dek` and the archive becomes unreadable
  everywhere, including old S3 versions and backups (crypto-shredding).
- **Back up the master key.** Without it, the archives cannot be recovered.

## API quick reference

All endpoints need `Authorization: Bearer <access token>` from Keycloak.

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/v1/connection-requests` | client | multipart: `request` (JSON), `cerereRacordare`, `actIdentitate`, `aviz` |
| GET | `/api/v1/connection-requests` | client | your requests |
| GET | `/api/v1/connection-requests/{id}` | client | one of yours |
| GET | `/api/v1/admin/connection-requests` | admin | `status`, `region`, `page`, `size` filters; oldest first |
| GET | `/api/v1/admin/connection-requests/{id}` | admin | decrypted details + documents + other requests from the same CNP/CUI |
| GET | `/api/v1/admin/connection-requests/{id}/archive` | admin | the zip |
| GET | `/api/v1/admin/connection-requests/{id}/documents/{docId}` | admin | one document |
| POST | `/api/v1/admin/connection-requests/{id}/decision` | admin | `{"decision":"ACCEPTED"}` or `{"decision":"REJECTED","reason":"…"}` |

Errors are RFC 9457 problem documents with a stable `code` (e.g.
`invalid_identity_number`, `unsupported_document_format`, `already_decided`,
`storage_unavailable`, `integrity_check_failed`) and the request's `traceId`.

`request` part example (send it with `Content-Type: application/json`):

```json
{
  "applicantType": "PERSOANA_FIZICA",
  "requestType": "LOC_CONSUM_NOU",
  "applicant": { "fullName": "Ion Popescu", "identityNumber": "<CNP>", "email": "ion@example.com",
                 "phone": "+40 721 000 000", "address": "str. …, Voluntari" },
  "consumptionPlace": { "county": "IF", "locality": "Voluntari", "address": "str. …" },
  "requestedPowerKw": 9.5,
  "permit": { "type": "AVIZ_AMPLASAMENT", "number": "AA-2026-0042", "issuer": "Sucursala Ilfov",
              "issuedOn": "2026-10-01", "validUntil": "2027-10-01" }
}
```

`county` is the two-letter code: B, IF, GR (Muntenia), TM, AR, CS, HD (Banat),
CT, TL, CL, IL (Dobrogea). `permit.type` is `AVIZ_AMPLASAMENT` or `CERTIFICAT_URBANISM`.
