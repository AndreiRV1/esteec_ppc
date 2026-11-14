# FileGrid showcase (about 15 minutes)

One pass through everything FileGrid does, in the order that tells the story: a person asks for a
grid connection, gets approved, sees their consumption, is told about a fault in their area; staff
upload field data; the admin picks storage per category and watches the system under load.

## Before the audience arrives

```sh
make up            # whole stack behind https://localhost:8443 (accept the local certificate once)
make demo-setup    # meters, client logins, 2 days of history, faults and videos; prints the logins
```

Open three browser windows (or profiles, so the sessions do not mix): **Client**, **Employee**, **Admin**.
Have these files at hand:

| File | Used in |
|---|---|
| `demo/documents/cerere-racordare.pdf` | step 1, "Cererea de racordare" |
| `demo/documents/act-identitate.pdf` (or `certificat-inregistrare.pdf` for a company) | step 1, "Actul de identitate" |
| `demo/documents/aviz-amplasament.pdf` (or `certificat-urbanism.pdf`) | step 1, "Avizul sau certificatul" |
| `demo/faults/avarie-voluntari.json` | step 5 |
| a video from `backend/fault_videos/data/videos/` (the path is printed by `make demo-setup`) | step 6 |

The documents are specimens with fictitious data; the form values below match them.

## 1. Registration: a racordare without an account (Client window)

1. Open the app: the login page appears. Click **Request a connection**.
2. Fill in the form (values from the specimen documents):
   - Persoană fizică · **Ion Demo** · CNP **1960529460012** · ion.demo@example.ro · +40 721 000 000
   - Address: Str. Exemplului nr. 1, Voluntari, jud. Ilfov
   - Loc de consum: **Ilfov**, **Voluntari**, Str. Exemplului nr. 2 · Loc de consum nou · **9,5 kW**
   - Aviz de amplasament **AA-2026-0042**, issuer "Operatorul de distribuție – Sucursala Ilfov",
     issued 01.11.2026, valid until 01.11.2027
   - Attach the three PDFs, tick the privacy/terms box, **Depune cererea**.
3. **Point out:** the page shows an **ID client** and a **password**, once. The documents were zipped,
   encrypted (AES-256-GCM, one key per archive) and stored per the storage policy; the CNP is kept only
   as an HMAC.
4. **Autentifică-te** with that ID and password. The client sees the request as *în așteptare* and the
   message that a meter is assigned after approval.

## 2. Review and approval (Admin window, `admin@filegrid.local`)

1. **Cereri de racordare**: filter *În așteptare*, open the request.
2. Download one document and the archive. **Point out:** the browser checks the SHA-256 the server sent;
   a corrupted file is never delivered.
3. **Aprobă cererea**. (Rejecting needs a reason; an admin cannot decide their own request.)

## 3. The customer portal (Client window)

1. Reload. The client now has a meter in Voluntari, picked from the installed fleet.
2. **Point out:** month-to-date register, the consumption chart (hour / day), readings arriving live
   from the simulated meters every minute.
3. **Schimbă parola**: Keycloak's own page; log in again with the new password.
4. For a client with history, log in as `client.demo@filegrid.local` or any `cl-…` from
   `db/seed/clients.csv`: two days of consumption, anomalies marked on the chart.

## 4. Meter ingest (talk over the Admin window: **Monitorizare** → Grafana "Contoare")

- About 2,000 simulated meters report through the same public API real meters would use (`X-API-Key`).
- Raw messages are stored first (no loss, idempotent on `(meter, message)`), then processed into
  readings; duplicates, late and out-of-order messages are handled, anomalies are flagged, never dropped.

## 5. A fault in the client's area (Employee window, `employee.demo@filegrid.local`)

1. **Avarii** → upload `demo/faults/avarie-voluntari.json`: the form fills itself from the file
   (Muntenia, Ilfov, Voluntari, HIGH). Set **Momentul avariei** to a few minutes ago, **Încarcă**.
2. Client window: reload. **A red banner** announces the fault in Voluntari.
3. Employee (or admin): **Caută și descarcă** → the fault → **Marchează rezolvată**. Client reloads: banner gone.

## 6. Drone video, resumable (Employee window)

1. **Video dronă** → pick the video, fill line code and flight time, **Încarcă**.
2. **Point out:** the file goes up in 8 MB parts; **Pauză** (or close the tab) and resume: it continues from
   the last part. On completion the server reads the object back and compares the SHA-256.

## 7. Storage policy per category (Admin window)

1. **Politici de stocare**: fault records → FTP, connection documents → S3 (encrypted), videos → S3.
2. Switch *Avarii / Muntenia* to **S3**, **Salvează**. Upload another fault (step 5): it lands in S3. Files
   already stored stay where they are and remain readable. Every change is audited.

## 8. Statistics and monitoring (Admin window)

1. **Statistici**: measured latency per category and storage, normal vs busiest day, grid insights per
   region (meters, anomalies, faults, hotspots).
2. **Monitorizare**: *Alerte active* (empty when all is well), then Grafana (admin-only SSO): *Overview*,
   *Stocare*, *Contoare*.
3. **One operation, end to end:** copy the "ID operație" shown after the fault upload in step 5 (or click
   *Urmărește operația* on the request page from step 2), paste it in **Urmărește o operație**. Grafana shows
   one trace: proxy → backend → `db record file` → `storage put` (file id, FTP path, bytes) → `db confirm file`.

## 9. Resilience and scale (terminal, optional)

```sh
docker compose stop ftp                  # fault uploads on FTP -> 503 storage_unavailable; meters, racordare keep working
                                         # ~2 min later: StorageBackendDown on Monitorizare + e-mail in https://localhost:8443/mailpit/
                                         # ("[FileGrid] Cerință în pericol: Categoriile stocate pe FTP ...")
docker compose start ftp                 # "Rezolvat" e-mail follows
docker compose up -d --scale backend=2   # two replicas behind the proxy; the meter processor never double-counts
make gen-storm && make seed-data DATA_ARGS="--workers 8"   # 10x storm of fault files; watch Grafana
```

Kubernetes manifests with a deployment and autoscaler per category are in `k8s/` (see `k8s/README.md`).

## Reset

`make clean` deletes all data (asks no questions); then `make up && make demo-setup` again.
