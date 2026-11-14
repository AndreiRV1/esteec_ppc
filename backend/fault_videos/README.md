# FileGrid PPC — date de test pentru `FAULT_RECORD` și `DRONE_VIDEO`

Acest modul (`backend/fault_videos/`) face două lucruri:

1. **generează** fișiere de test (înregistrări de avarie JSON de 1–10 MB și video-uri de dronă MP4, inclusiv unul de peste 1 GB), împărțite pe cele 3 regiuni;
2. le **încarcă și le verifică prin API-ul FileGrid**, exact ca un angajat din interfață.

Nu se conectează la PostgreSQL, FTP sau S3. FileGrid este singurul punct de intrare: politica de stocare, criptarea, checksum-urile, rândurile din baza de date și jurnalul de audit sunt treaba backend-ului.

Folosește doar biblioteca standard Python 3 (fără `pip`). `ffmpeg` este opțional: cu el se generează clipuri H.264 reale cu telemetrie HUD; fără el se scriu containere `.mp4` sintetice (structură ISO-BMFF validă, conținut pseudo-aleator, **neredabile**), suficiente pentru testele de transfer, reluare și integritate.

## 1. Ce se generează

```text
backend/fault_videos/data/            # ignorat de git
├── manifest.json                     # index pe regiuni și categorii
├── faults/<REGION>/fault_<REGION>_<stație>_<ts>_<id>.json   (+ .meta.json)
└── videos/<REGION>/video_<REGION>_<linie>_<ts>_<id>.mp4     (+ .meta.json)
```

- **Avarii (`.json`)**: metadatele avariei (`region`, `county`, `locality`, `substation_code`, `occurred_at`, `restored_at`, `severity`, `cause`, `affected_customers`, `description`) și oscilograma trifazată de la releul de protecție (5 kHz).
- **Video-uri (`.mp4`)**: fișierul video și un `.meta.json` cu `region`, `county`, `line_code`, `drone_id`, `flight_started_at`, `duration_seconds`, `notes` și `sha256`.
- După încărcare, `.meta.json` primește cheia `filegrid` (`id`, `fileId`, `sha256`, `uploadedAt`, adresa API-ului). O încărcare video începută și neterminată este ținută minte în `filegridUpload`, ca să poată fi reluată.

## 2. Cum se rulează

Din rădăcina proiectului, cu sistemul pornit (`make up`):

```sh
make gen-data          # 2 avarii + 1 video pe regiune
make seed-data         # încarcă prin API tot ce nu a fost încă încărcat
make verify-data       # descarcă prin API și compară SHA-256 cu fișierele locale
make data-status       # sumar local + totaluri din FileGrid pe regiuni

make gen-storm         # vârf de furtună: 10x avarii HIGH/CRITICAL + video-uri
make seed-data DATA_ARGS="--workers 8"      # ...trimise în paralel
make gen-large-video   # un video de ~1,05 GB (test de scalabilitate)
```

`GEN_ARGS` trece opțiuni către `generate`, `DATA_ARGS` către `ingest` / `verify`.

Comportament:

- **Idempotent**: `seed-data` sare peste fișierele deja încărcate în aceeași instanță FileGrid (`--force` le retrimite).
- **Memorie constantă**: fișierele sunt citite de pe disc în bucăți de 1 MB, inclusiv video-ul de peste 1 GB.
- **Reluare**: video-urile se trimit în părți, în ordine. Dacă transferul se întrerupe (rețea, proces oprit), următoarea rulare întreabă serverul ce părți are și continuă de acolo.
- **Reîncercări**: erorile de rețea și răspunsurile 408/429/5xx sunt reîncercate cu backoff (implicit 6 încercări, `FILEGRID_HTTP_ATTEMPTS`).
- **Integritate**: fiecare parte are checksum (`X-Checksum-SHA256`); la final se compară SHA-256 întors de server cu cel local. `verify-data` semnalează `MISMATCH`, `CORRUPTED` (detectat de server), `MISSING`.
- La sfârșit se afișează, pe categorie: fișiere, MB, durată, MB/s, fișiere/s, timp mediu și p95. Codul de ieșire este diferit de zero dacă un fișier a eșuat.

## 3. Autentificare și adrese

Unealta se autentifică în Keycloak (realm `filegrid`, client public `filegrid-cli`) ca `employee.demo@filegrid.local`, cu parola `FILEGRID_DEMO_EMPLOYEE_PASSWORD` din `.env` (rol `employee`). Token-ul este reînnoit automat în timpul încărcărilor lungi.

Variabile (din mediu sau `.env`; mediul are prioritate):

| Variabilă | Implicit | Rol |
|---|---|---|
| `FILEGRID_PUBLIC_URL` | `https://localhost:8443` | Punctul public de intrare: API la `<url>/api/v1`, Keycloak la `<url>/auth` |
| `FILEGRID_API_URL` | — | Adresa backend-ului, dacă nu treci prin proxy (ex. `http://localhost:8080`) |
| `FILEGRID_AUTH_URL` | — | Adresa Keycloak, dacă nu treci prin proxy (ex. `http://localhost:8180`) |
| `FILEGRID_CA_FILE` | — | Certificatul CA al proxy-ului local |
| `FILEGRID_TLS_INSECURE` | `0` | `1` = nu verifică certificatul TLS (doar pentru proxy-ul local autosemnat) |
| `FILEGRID_UPLOAD_USER` / `FILEGRID_UPLOAD_PASSWORD` | utilizatorul demo | Alt cont cu rol `employee` |

Exemplu cu backend-ul pornit din IDE (`make dev`):

```sh
FILEGRID_API_URL=http://localhost:8080 FILEGRID_AUTH_URL=http://localhost:8180 make seed-data
```

## 4. Opțiuni CLI

```sh
python3 backend/fault_videos/fault_videos.py generate \
  --regions MUNTENIA,BANAT,DOBROGEA --profile normal \
  --faults-per-region 3 --fault-min-mb 1.5 --fault-max-mb 5.0 \
  --videos-per-region 1 --video-size-mb 25

python3 backend/fault_videos/fault_videos.py generate --regions DOBROGEA --profile storm --faults-per-region 20

python3 backend/fault_videos/fault_videos.py ingest --region MUNTENIA --category FAULT_RECORD --workers 8
python3 backend/fault_videos/fault_videos.py verify --category DRONE_VIDEO
python3 backend/fault_videos/fault_videos.py clean      # șterge fișierele generate local
```
