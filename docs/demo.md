# FileGrid showcase (about 30 minutes)

One storyline, "a day in the life of the grid": a person asks for a connection and gets approved; their
meter reports and they see their consumption. A storm brings a fault, a burst of field data and a drone
inspection. Then things break: a storage goes down, a file is corrupted, the link is bad. FileGrid keeps
working, raises the alarm and proves every file it returns. The admin closes with the numbers and what
they mean.

Every requirement in the spec is shown live at least once; see the [coverage table](#coverage-spec--act)
at the end. Measured numbers for the terminal acts are in [`evidence.md`](evidence.md), as a fallback
if something misbehaves on stage.

**Two presenters** work best: one *drives* (browser and terminal), one *narrates* the "Say" lines.
Short on time? Run acts 1, 2, 3, 5, 6, 8, 9a, 9c and 12 (about 15 minutes).

## Before the audience arrives (30 min ahead)

```sh
make up                 # whole stack behind https://localhost:8443 (accept the local certificate once)
make demo-setup         # meters, client logins, 2 days of history, faults, videos; prints the logins
make gen-large-video    # a 1.05 GB drone video for act 6b (takes a minute, do it now)
```

Open and arrange:

| Window | What | Login |
|---|---|---|
| **Client** (browser profile 1) | https://localhost:8443 | created live in act 1 |
| **Employee** (profile 2) | https://localhost:8443 | `employee.demo@filegrid.local` |
| **Admin** (profile 3) | https://localhost:8443, plus tabs: Grafana *Overview*, Mailpit (`/mailpit/`) | `admin@filegrid.local` |
| **Terminal** | repo root, large font | |

Passwords are in `.env` (`FILEGRID_ADMIN_PASSWORD`, `FILEGRID_DEMO_EMPLOYEE_PASSWORD`). Use separate
browser profiles so the sessions don't mix.

Files at hand:

| File | Act |
|---|---|
| `demo/documents/cerere-racordare.pdf`, `act-identitate.pdf`, `aviz-amplasament.pdf` | 1 |
| `demo/faults/avarie-voluntari.json` | 5 |
| a video from `backend/fault_videos/data/videos/` (path printed by `make demo-setup`) | 6 |

Dry run: `make demo-security` and `make demo-corruption` both print their checks in seconds. Run them once
before the audience arrives.

---

## Act 0. One command, one entry point (1 min, Terminal)

```sh
docker compose ps        # ~16 services: Postgres, MinIO (S3), vsftpd (FTP), Keycloak, backend, simulator,
                         # Prometheus, Loki, Tempo, Alloy, Alertmanager, Grafana, Mailpit, proxy, frontend
```

**Say:** "A clean machine needs Docker and one command: `make up`. It generates its own secrets, starts
three separate storages (FTP, S3, database) and the observability stack, and loads the meter fleet.
Everything goes through one TLS entry point."

## Act 1. Registration: a racordare without an account (3 min, Client)

1. Open the app: the login page appears. Click **Request a connection**.
2. Fill in the form with the values from the specimen documents:
   - Persoană fizică · **Ion Demo** · CNP **1960529460012** · ion.demo@example.ro · +40 721 000 000
   - Address: Str. Exemplului nr. 1, Voluntari, jud. Ilfov
   - Loc de consum: **Ilfov**, **Voluntari**, Str. Exemplului nr. 2 · Loc de consum nou · **9,5 kW**
   - Aviz de amplasament **AA-2026-0042**, issuer "Operatorul de distribuție – Sucursala Ilfov",
     issued 01.11.2026, valid until 01.11.2027
   - Attach the three PDFs, tick the privacy/terms box, **Depune cererea**.
3. The page shows an **ID client** and a **password**, once. **Autentifică-te** with them: the request
   is *în așteptare*.

**Say:** "The three documents were zipped and encrypted with AES-256-GCM, one key per archive and the
key itself wrapped by a master key. They were stored where the policy says. The CNP is never stored,
only an HMAC of it, which is enough to recognise a returning applicant. We'll try to steal these files
in act 10."

## Act 2. Review and approval (2 min, Admin)

1. **Cereri de racordare** → filter *În așteptare* → open Ion Demo's request.
2. Download one document, then the whole archive.
3. **Aprobă cererea**. Rejecting needs a reason, and an admin cannot decide their own request.

**Say:** "Every download carries the SHA-256 FileGrid recorded at upload. The browser recomputes it, and
the server checks it before it sends a single byte. A file that does not match is never delivered;
act 9a breaks one on purpose. Decisions and downloads of personal data are audited."

## Act 3. The customer portal (2 min, Client)

1. Reload. Ion now has a meter in Voluntari, picked from the installed fleet.
2. Show the month-to-date register and the consumption chart (hour / day). New readings arrive every
   minute from the simulated meters.
3. For a client with history: log in as `client.demo@filegrid.local` (or any `cl-…` from
   `db/seed/clients.csv`). Two days of consumption, with anomalies marked ▲ on the chart.
4. Optional: **Schimbă parola** opens Keycloak's own page.

## Act 4. Meter readings: none lost, none doubled (2 min, Admin → Grafana *Contoare*)

Point at **Ingest**, **Duplicates absorbed**, **Rejected messages**, **Backlog** and **Anomaly rate**.

```sh
# Proof in SQL: 0 means no meter interval was ever counted twice.
docker compose exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
  SELECT count(*) FROM (SELECT meter_id, interval_start FROM filegrid.meter_reading GROUP BY 1,2 HAVING count(*) > 1) d"'
```

**Say:**
- "About 2,000 simulated meters send to the same public API real meters would use, with an API key.
  The simulator resends, reorders and sends late on purpose."
- "Every message is first stored raw. Storing is idempotent on (meter, message), so retries are
  absorbed. Readings are then processed with `FOR UPDATE SKIP LOCKED`, which is safe with any number of
  instances. Anomalies are flagged, never dropped: bills are computed from this."
- "The simulator volume is configurable. The `peak` profile replays a day at 10x speed."

## Act 5. A fault in the client's area (2 min, Employee + Client)

1. Employee → **Avarii** → upload `demo/faults/avarie-voluntari.json`. The form fills itself from the file
   (Muntenia, Ilfov, Voluntari, HIGH). Set **Momentul avariei** to a few minutes ago → **Încarcă**.
   Copy the **ID operație** shown: act 11 uses it.
2. Client window: reload. **A red banner** announces the fault in Voluntari. Dispatch had it immediately.
3. Employee: **Caută și descarcă** → search by region or county → download (SHA-256 verified) →
   **Marchează rezolvată**. The client reloads and the banner is gone.

## Act 6. Drone video: big and resumable (3 min, Employee + Terminal)

**6a. In the browser.**
1. **Video dronă** → pick the video, line code, flight time → **Încarcă**.
2. Show the parts going up: hashing, then 8 MB parts and the progress.
3. Press **Pauză**, or close the tab and reopen it. It resumes from the last part the server confirmed.
4. On completion the server reads the object back and compares the SHA-256.

**6b. Over 1 GB, constant memory.**

```sh
docker stats --no-stream filegrid-backend-1                             # memory before
make seed-data DATA_ARGS="--category DRONE_VIDEO"                         # uploads the 1.05 GB video
docker stats --no-stream filegrid-backend-1                             # run in a 2nd terminal during it
```

**Say:**
- "The file never sits in memory: it streams part by part into S3 multipart. We measured the backend
  at 353 MiB before and 392 MiB while a 1 GB file was going through."
- "Upload progress lives in Postgres, so any backend instance can take the next part."

## Act 7. Storage policy without code (2 min, Admin)

1. **Politici de stocare**: per category and region, choose the backend (FTP / S3 / DB), target, size limit
   and encryption. Connection documents are always encrypted; the database is limited to small files.
2. Switch *Avarii / Banat* to **S3** → **Salvează**. Upload another fault for Banat (act 5 form): it lands
   in S3. Files already stored stay where they are and remain readable. Every change is audited.
3. Switch it back to **FTP**: act 9 needs fault records on FTP.

## Act 8. Storm: 10x faults in one category (4 min, Terminal + Grafana *Overview*)

Put Grafana *Overview* on the big screen (*Throughput / Latency p95 by category*), then:

```sh
make demo-storm                  # 300 fault files (~800 MB), 8 workers; probes the other categories
make demo-storm REPLICAS=2       # the same with two backend instances (no code change)
```

The script prints the p50/p95 of the *other* categories before and during the storm, and the meter
ingest rate. Reference numbers from our run: 0 errors in any other category, meter ingest unchanged
(188 → 189 messages/min), 300/300 files stored. See `evidence.md`.

**Say:**
- "Capacity grows by adding instances: the proxy finds them by DNS and Prometheus scrapes each one."
- "On one laptop two instances share the same CPU. The real target is Kubernetes: `k8s/` has one
  Deployment and one autoscaler per category. A fault storm scales `backend-faults` while connection
  requests and videos stay where they are."

Scale back afterwards: `docker compose up -d --scale backend=1 backend`.

## Act 9. Things break (6 min, Terminal + Admin)

**9a. Corruption is detected on retrieval.**

```sh
make demo-corruption     # upload → download OK → change the file on FTP behind FileGrid → download refused
```

Shows: HTTP 500 `integrity_check_failed`, file marked `CORRUPTED`. **FileIntegrityFailure** appears under
Monitorizare → *Alerte active* and as an e-mail in Mailpit.

**9b. Slow, lossy, unstable link.**

```sh
make demo-degraded       # 100 ms latency, 1% loss, a 20 s outage in the middle of a video upload
```

The upload stalls, retries with backoff and resumes from the last confirmed part. It finishes by itself,
then every file is downloaded again and its SHA-256 compared (3/3).

**9c. A storage goes down.**

```sh
docker compose stop ftp
```

Try a fault upload for a region on FTP: a clean `503 storage_unavailable`. Meanwhile racordare (S3) and
meters (database) keep working; show a client login and the Grafana *Contoare* ingest. Move on to act 10.
About 2 minutes later, **StorageBackendDown** shows in *Alerte active*, and Mailpit gets
*"[FileGrid] Cerință în pericol: Categoriile stocate pe FTP …"*. Then:

```sh
docker compose start ftp # the "Rezolvat" e-mail follows; files confirmed before the outage are all readable
```

## Act 10. Security (2 min, Terminal)

```sh
make demo-security
```

It shows:
1. Only the proxy port is published. Postgres, MinIO and FTP refuse connections from outside.
2. The API answers 401 without a token, and the only port is TLS.
3. With the MinIO root credentials (an attacker inside the storage network), Ion Demo's archive is
   ciphertext: not a ZIP, and no readable PDF or CNP in it. The database holds only an HMAC.
4. `.env` and `db/seed/` are not in Git. Secrets are generated by `make up` and passed to Kubernetes
   by `k8s/create-secrets.sh`.

## Act 11. Observability: from one request to storage (2 min, Admin → Monitorizare)

1. **Urmărește o operație**: paste the ID operație from act 5 (or *Urmărește operația* on the request
   page from act 2). Grafana shows one trace: proxy → backend → `db record file` → `storage put` (file id,
   FTP path, bytes) → `db confirm file`. From any log line you can jump to its trace.
2. Grafana *Overview*: per category, throughput, error rate and latency p50 / p95 / p99. *Storage*: the same
   per storage backend, side by side.
3. **Alerte active**: everything that fired in act 9, now resolved. The 16 alert rules map to the business
   requirements in spec table 2.1: meters not processed or rejected, faults slow or failing, documents
   failing or accessed without permission, video uploads failing, integrity, storage down.

## Act 12. The numbers and what they mean (3 min, Admin → Statistici). Close here.

Walk through the three questions from spec §5:

1. **Which storage suits which category, and why?** Measured latency and error rate per category and
   storage, with the recommendation the page derives from them.
2. **Peak day vs normal day.** Busiest period vs a normal one, which includes the storm from act 8.
   With fewer than 3 days of traffic it compares by hour.
3. **What the data says about the grid.** Per region: meters, anomaly rate, faults, hotspots.

**Say:** "FileGrid doesn't just keep the files. It tells you where they should live, how the system
held up under the storm, and where the grid needs attention."

---

## Coverage: spec → act

| Spec | Requirement | Act |
|---|---|---|
| §3 | Store and retrieve all four categories | 1–2 documents, 4 meters, 5 faults, 6 videos |
| §3 | FTP, S3 and DB used, each a separate service | 0, 7 |
| §3 | Policy per category configurable without code | 7 |
| §3 | Retrieved file identical, and FileGrid proves it | 2, 5, 6, 9a |
| §3 | Clean machine, one command, README | 0 (`make up`) |
| §3 | UI to upload, search and download | 1, 2, 5, 6 |
| §3 | Meter simulator, configurable volume (normal / peak) | 4 |
| §4 | 10x spike in one category does not degrade the others | 8 |
| §4 | Capacity by adding instances, no code change | 8 (`REPLICAS=2`), `k8s/` HPA per category |
| §4 | > 1 GB without resources growing with file size | 6b |
| §4 | p50/p95/p99, throughput, errors per category and storage | 11 |
| §4 | One operation traced end to end | 11 |
| §4 | Team alerted when a business requirement is at risk | 9a, 9c, 11 |
| §4 | Storages not reachable from outside | 10 |
| §4 | TLS from outside, authenticated access | 10, login in every act |
| §4 | Direct storage access exposes no personal data | 1, 10 |
| §4 | No passwords or keys in the repository | 10 |
| §4 | A storage down stops nothing else, loses nothing confirmed | 9c |
| §4 | Slow, lossy link: transfers finish or resume by themselves | 6a, 9b |
| §4 | Retries don't double meter readings; corruption detected | 4, 9a |
| §5 | Statistics with interpretation for decisions | 12 |

## If something goes wrong on stage

- A terminal act fails: show its numbers in `evidence.md` and keep going.
- Keycloak answers 503 right after a restart. It needs about a minute; `scripts/keycloak-sync.sh` retries.
- The frontend alone, with no backend: `cd frontend && npm run start:mock` (fixture data; switch roles
  top right).

## Reset

`make clean` deletes all data without asking. Then run `make up && make demo-setup` again.
