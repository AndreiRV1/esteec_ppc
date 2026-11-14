# FileGrid PPC

The single entry point for all field data at Rețele Electrice România: smart-meter readings, substation fault records, customer connection documents (`racordare`) and drone inspection videos. Requirements: [`FileGrid PPC.pdf`](FileGrid%20PPC.pdf).

## What is implemented

- **Single TLS entry point (`infra/proxy/Caddyfile`)**: Caddy reverse proxy at `FILEGRID_PUBLIC_URL` (default `https://localhost:8443`), routing `/api/*` to the backend, `/auth/*` to Keycloak, `/grafana/*` to Grafana, `/mailpit/*` to Mailpit, and `/` to the Angular web application (`frontend/`) or a fallback placeholder page.
- **Storage layer (`compose.yaml`, `db/migration/`)**: PostgreSQL 18, MinIO (S3) and vsftpd (FTP), isolated on the internal `backend` network and reachable only through FileGrid, with runtime-configurable per-category and per-region policies (`storage_policy`).
- **Authentication & authorization (`infra/keycloak/`)**: Keycloak OIDC (`filegrid` realm) with roles `client`, `employee` and `admin`, plus `scripts/seed-clients.py` for linking demo customer logins to smart meters.
- **Backend API (`backend/`, Spring Boot 4 / Java 25)**:
  - Smart-meter ingest (`POST /api/v1/meter-readings` with `X-API-Key`, idempotent `202`/`200` raw storage and `FOR UPDATE SKIP LOCKED` background processing with anomaly detection).
  - Substation fault records (`POST`/`GET /api/v1/fault-records`, streamed multipart upload and SHA-256 verified download).
  - Resumable multipart drone inspection videos (`POST /api/v1/drone-videos/uploads`, stateless part tracking in PostgreSQL, constant memory use for 1 GB+ files).
  - Customer connection requests (`racordare`: envelope-encrypted ZIP archives in S3 with AES-256-GCM, admin review queue, and append-only audit trail).
  - Customer portal (`/api/v1/me/**`: meter profile, consumption series/summary, and active area faults) and runtime storage-policy administration (`/api/v1/admin/storage-policies/**`).
  - Full contract: [`docs/api.md`](docs/api.md) and [`docs/racordare.md`](docs/racordare.md).
- **Web frontend (`frontend/`)**: Angular SPA for `client`, `employee` and `admin` workflows, including resumable chunked video upload, runtime storage-policy switching, and embedded Grafana dashboards.
- **Observability (`infra/observability/`)**: Prometheus (DNS service discovery across backend replicas + Blackbox storage probes), Alertmanager + Mailpit (alert rules per business requirement of PDF Table 2.1), Loki + Alloy (ECS JSON logs with `trace_id`), Tempo (OTLP traces down to storage spans), and Grafana (`filegrid-overview`, `filegrid-storage`, `filegrid-meters` dashboards, Keycloak `admin`-only SSO). Details: [`docs/platform.md`](docs/platform.md).
- **Simulators & generators**: Smart-meter fleet simulator ([`simulator/README.md`](simulator/README.md)) and regional fault JSON / drone MP4 generator & API uploader ([`backend/fault_videos/README.md`](backend/fault_videos/README.md)).
- **Kubernetes manifests (`k8s/`)**: Kustomize deployment splitting the backend by data category with independent `HorizontalPodAutoscaler`s, dedicated `meter-processor`, `NetworkPolicy` storage isolation, and `PodDisruptionBudget`s. Guide: [`k8s/README.md`](k8s/README.md).

## Quick start

Requirements: Docker with Compose v2, `make` and `openssl`. No local JDK or Node installation is required: builds and tests run in containers.

```sh
make up      # generates .env with random secrets on first run, then starts the full stack
make demo-setup  # data for the showcase: meters, client logins, history, faults, videos; prints the logins
make demo    # optional: end-to-end racordare walkthrough through the TLS proxy (needs curl + jq)
make demo-storm      # optional: 10x fault storm, latency of the other categories (REPLICAS=2 for two backends)
make demo-degraded   # optional: upload over a degraded link (latency, loss, outage) that resumes by itself
make demo-corruption # optional: a file corrupted on storage is detected and never delivered
```

Measured results of these and the Kubernetes run: [`docs/evidence.md`](docs/evidence.md).

**Showcase:** follow [`docs/demo.md`](docs/demo.md) (about 15 minutes, every feature). The specimen documents
for a racordare are in `demo/documents/`, fault reports in `demo/faults/`, a tagged drone clip in `demo/videos/`
(regenerate all with `make demo-files`). Forms fill themselves from these files: PDF text, OCR of scans, MP4 metadata.

In `make up` mode, **only the TLS proxy port (`127.0.0.1:8443`) is published to the host**. Accept Caddy's local CA certificate once in the browser (or pass `--cacert` extracted from `proxy:/data/caddy/pki/authorities/local/root.crt`).

| What | URL (`make up`) | Login |
|---|---|---|
| Web UI | `https://localhost:8443/` | Keycloak SSO (`client`, `employee`, `admin`) |
| API docs (Swagger UI) | `https://localhost:8443/swagger-ui.html` | Bearer token |
| Self-service account / registration | `https://localhost:8443/auth/realms/filegrid/account/` | New accounts receive the `client` role |
| Grafana dashboards | `https://localhost:8443/grafana/` | Keycloak SSO (`admin@filegrid.local` only) |
| Alert inbox (Mailpit) | `https://localhost:8443/mailpit/` | Local operator access |
| Keycloak admin console | `https://localhost:8443/auth/admin/` | `admin` / `KEYCLOAK_ADMIN_PASSWORD` from `.env` |
| Seeded accounts | `admin@filegrid.local`, `employee.demo@filegrid.local`, `client.demo@filegrid.local` | `FILEGRID_ADMIN_PASSWORD`, `FILEGRID_DEMO_EMPLOYEE_PASSWORD`, `FILEGRID_DEMO_CLIENT_PASSWORD` in `.env` |

Get a bearer token for CLI scripts using the `filegrid-cli` client:

```sh
set -a; . ./.env; set +a
curl -ks "${FILEGRID_PUBLIC_URL:-https://localhost:8443}/auth/realms/filegrid/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=filegrid-cli \
  -d username=admin@filegrid.local --data-urlencode "password=$FILEGRID_ADMIN_PASSWORD" | jq -r .access_token
```

### Local IDE mode (`make dev`)

To run the backend from your IDE while keeping storages, Keycloak, observability, and the TLS proxy in Docker:

```sh
make dev                                  # exposes storages & Keycloak on 127.0.0.1; proxy forwards /api to host :8080
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run
```

See [`docs/platform.md`](docs/platform.md) for the full table of `make up` vs `make dev` ports, token issuer details, and horizontal scaling (`docker compose up -d --scale backend=2`).

### Data generation & simulation targets

```sh
make seed                              # generate simulated meter fleet and load into filegrid.meter
make seed-clients CLIENTS=5            # create Keycloak + customer_account logins (writes db/seed/clients.csv)
make sim                               # extra run with custom flags (`make up` already starts a real-time simulator; SIMULATOR=0 to skip)
make gen-data                          # generate regional fault JSONs (1-10 MB) and drone MP4 videos
make gen-storm                         # generate 10x storm spike dataset
make gen-large-video                   # generate >1 GB drone video for constant-memory multipart testing
make seed-data DATA_ARGS="--workers 8" # upload generated faults & videos through the FileGrid API
make verify-data                       # download uploaded files via API and verify SHA-256
```

Other targets: `make test`, `make ps`, `make logs`, `make psql`, `make migrate`, `make env`, `make down`, `make clean` (**deletes all data volumes**).

## Layout

```
compose.yaml              full stack; only the TLS proxy publishes a host port
compose.dev.yaml          IDE overlay: storages & Keycloak on 127.0.0.1, proxy forwards /api to host
.env.example              configuration template; `make env` generates .env with random secrets
backend/                  Spring Boot 4 API (meters, faults, drone videos, racordare, storage, crypto, audit)
backend/fault_videos/     synthetic fault JSON + drone MP4 generator and API ingestion/verification CLI
frontend/                 Angular web application (client, employee, admin portals)
simulator/                smart-meter fleet simulator (normal & 10x peak profiles, anomaly injection)
db/migration/             Flyway SQL migrations (V1 baseline, V2 submitter, V3 customer accounts)
infra/proxy/              Caddyfile TLS reverse proxy and fallback placeholder page
infra/observability/      Prometheus, Alertmanager, Blackbox, Loki, Alloy, Tempo, and Grafana dashboards
infra/postgres/initdb/    first-boot least-privilege database roles (filegrid_migrator, filegrid_app, keycloak)
infra/keycloak/realm/     Keycloak realm import (roles, OIDC clients, seeded users)
infra/minio/              idempotent S3 bucket bootstrap and IAM policy
infra/vsftpd/             hardened Alpine + vsftpd container image
k8s/                      Kustomize manifests (category-split deployments, HPAs, NetworkPolicies, Ingress)
scripts/                  gen-env.sh, demo-*.sh acts, make-demo-documents.py / make-demo-video.sh, seed-clients.py
demo/                     committed demo files: specimen PDFs (+ scanned copies for OCR), fault reports, tagged MP4
docs/                     API contract (api.md), platform & observability (platform.md), racordare (racordare.md)
```

## Further documentation

- [`docs/project.md`](docs/project.md) — the whole project in one place: architecture, every component, how each demo step is implemented, known limits.
- [`docs/demo.md`](docs/demo.md) — the showcase script, with the data to generate and a spec coverage table.
- [`docs/api.md`](docs/api.md) — REST API contract, endpoints, error codes, and storage-policy rules.
- [`docs/platform.md`](docs/platform.md) — reverse proxy routing, OIDC issuer, Grafana SSO, dashboards, alerts, and compose scaling.
- [`docs/racordare.md`](docs/racordare.md) & [`docs/connection-documents.md`](docs/connection-documents.md) — connection request workflow and envelope encryption design.
- [`k8s/README.md`](k8s/README.md) — Kubernetes deployment, secret creation, static validation, and scaling/resilience proof.
