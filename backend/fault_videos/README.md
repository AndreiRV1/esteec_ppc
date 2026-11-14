# FileGrid PPC — Achiziție și Generare Date (`FAULT_RECORD` & `DRONE_VIDEO`)

Acest modul (`backend/fault_videos/`) gestionează generarea fișierelor de test și achiziția/ingestia lor în infrastructura de stocare pornită cu `make dev` (PostgreSQL 18, MinIO S3, vsftpd FTP), respectând integral schema din [`db/migration/V1__baseline.sql`](../../db/migration/V1__baseline.sql) și cerințele din [`FileGrid PPC.pdf`](../../FileGrid%20PPC.pdf).

Nu necesită pachete Python externe (`pip`): folosește exclusiv biblioteca standard Python 3 + `ffmpeg` (pentru generarea videoclipurilor `.mp4` cu telemetrie HUD).

---

## 1. Împărțirea pe regiuni (`MUNTENIA`, `BANAT`, `DOBROGEA`)

Toate fișierele generate sunt împărțite în cele 3 regiuni de distribuție ale Rețelelor Electrice România și poartă tag-ul `region` pe 4 niveluri:

1. **Structura de directoare**:
   ```text
   backend/fault_videos/data/
   ├── manifest.json                     # Index global grupat pe regiuni și categorii
   ├── faults/
   │   ├── MUNTENIA/
   │   │   ├── fault_MUNTENIA_ST-MUN-BUC-NORD-110_<ts>_<id>.json
   │   │   └── fault_MUNTENIA_ST-MUN-BUC-NORD-110_<ts>_<id>.meta.json
   │   ├── BANAT/
   │   └── DOBROGEA/
   └── videos/
       ├── MUNTENIA/
       │   ├── video_MUNTENIA_LEA-110kV-MUN-BUC-OTOPENI-01_<ts>_<id>.mp4
       │   └── video_MUNTENIA_LEA-110kV-MUN-BUC-OTOPENI-01_<ts>_<id>.meta.json
       ├── BANAT/
       └── DOBROGEA/
   ```
2. **În interiorul fișierelor**:
   - **Avarii (`.json`, 1–10 MB)**: câmpul rădăcină `"region": "MUNTENIA" | "BANAT" | "DOBROGEA"` și obiectul `"fault_record"` cu toate coloanele din tabela `filegrid.fault_record` (`region`, `county`, `locality`, `substation_code`, `occurred_at`, `restored_at`, `severity`, `cause`, `affected_customers`, `description`, `reported_by`) + seria de timp oscilografică trifazată (`5 kHz`, tensiuni `va_kv, vb_kv, vc_kv`, curenți `ia_a, ib_a, ic_a, in_a`, frecvență `freq_hz`) de la releul de protecție din stația de transformare.
   - **Video-uri dronă (`.mp4`, H.264)**:
     - **Overlay vizual (HUD)** randat direct pe cadrele video: afișează regiunea (`MUNTENIA` / `BANAT` / `DOBROGEA`), județul, codul liniei electrice (`LEA-110kV-...`), ID-ul dronei, coordonatele GPS și timpul de zbor.
     - **Tag-uri container MP4**: `comment="region=<REGION>;category=DRONE_VIDEO;..."`.
     - **Fișier companion `.meta.json`**: conține metadatele pentru tabela `filegrid.drone_video` (`region`, `county`, `line_code`, `drone_id`, `flight_started_at`, `duration_seconds`, `notes`, `uploaded_by`) și hash-ul `sha256`.
3. **În Storage (FTP / S3)**:
   - Fișierele sunt scrise sub căi partiționate pe regiune:
     - FTP: `/files/fault-records/<REGION>/<YYYY>/<MM>/<file_id>_<filename>.json`
     - S3: `filegrid-drone-videos/<REGION>/<YYYY>/<MM>/<file_id>_<filename>.mp4`

---

## 2. Cum se rulează (integrat cu `Makefile` și `make dev`)

Din rădăcina proiectului (`/home/catalin/Facultate/PPC-Hackathon/esteec_ppc`):

```sh
# 1. Pornește stack-ul de stocare pe 127.0.0.1 (PostgreSQL, MinIO S3, vsftpd FTP)
make dev

# 2. Generează setul de fișiere de test (avarii JSON 1-10 MB + video-uri MP4 pe cele 3 regiuni)
make gen-data

# 3. Încarcă fișierele generate în storage-urile pornite cu `make dev`
#    (citește dinamic `filegrid.storage_policy`, face upload în FTP / S3 și scrie în DB + audit_log)
make seed-data

# 4. Verifică integritatea SHA-256 bit-cu-bit a tuturor fișierelor stocate
make verify-data

# 5. Afișează sumarul local pe regiuni și starea din baza de date
make data-status
```

### Scenarii suplimentare (Furtună 10x și Video > 1 GB)

```sh
# Generează un vârf de furtună (10x volum de avarii HIGH/CRITICAL + video-uri post-furtună)
make gen-storm

# Generează un video MP4 de > 1 GB (~1.05 GB în ~2 secunde) pentru testul de scalabilitate
make gen-large-video
```

---

## 3. Rulare directă cu opțiuni avansate (`fault_videos.py`)

Poți apela direct scriptul CLI pentru control fin asupra regiunilor, dimensiunilor și numărului de fișiere:

```sh
# Generare doar pentru o regiune sau cu dimensiuni custom:
python3 backend/fault_videos/fault_videos.py generate \
  --regions MUNTENIA,BANAT,DOBROGEA \
  --profile normal \
  --faults-per-region 3 \
  --fault-min-mb 1.5 \
  --fault-max-mb 5.0 \
  --videos-per-region 1 \
  --video-size-mb 25

# Generare vârf de furtună doar în DOBROGEA (ex. 20 avarii de 2-8 MB):
python3 backend/fault_videos/fault_videos.py generate \
  --regions DOBROGEA \
  --profile storm \
  --faults-per-region 20 \
  --fault-min-mb 2.0 \
  --fault-max-mb 8.0

# Ingestie filtrată pe regiune sau categorie:
python3 backend/fault_videos/fault_videos.py ingest --region MUNTENIA --category FAULT_RECORD

# Curățarea fișierelor generate local:
python3 backend/fault_videos/fault_videos.py clean
```
