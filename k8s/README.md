# FileGrid Kubernetes Manifests (`k8s/`)

Kustomize manifests mirroring the Docker Compose platform while isolating each data category into its own autoscaled workload.

## Architecture

1. **Category-split stateless backend (`k8s/backend.yaml` + `k8s/ingress.yaml`)**:
   All five Deployments run the same `filegrid/backend:local` image, each with its own `HorizontalPodAutoscaler`, CPU/memory limits, and `PodDisruptionBudget`:
   - `backend-meters` (`/api/v1/meter-readings`, HPA `2..10`): ingests smart-meter readings into `meter_reading_raw`.
   - `backend-faults` (`/api/v1/fault-records`, HPA `2..10`): handles substation fault JSON uploads/downloads (10x storm bursts).
   - `backend-videos` (`/api/v1/drone-videos`, HPA `2..8`): handles resumable multipart drone video uploads/downloads. Progress is stored in PostgreSQL (`stored_file`), so any replica can serve any part.
   - `backend-general` (`/api`, `/v3/api-docs`, `/swagger-ui`, HPA `2..6`): handles connection requests (`racordare`), `/api/v1/me`, and `/api/v1/admin/**`.
   - `meter-processor` (HPA `2..6`, `FILEGRID_METER_PROCESSOR_ENABLED=true`): background worker turning pending `meter_reading_raw` rows into `meter_reading` rows using `FOR UPDATE SKIP LOCKED` so multiple replicas never double-process. All four HTTP API Deployments set `FILEGRID_METER_PROCESSOR_ENABLED=false`.
2. **Storages & vsftpd passive mode (`k8s/storages.yaml`)**:
   - `postgres` (PostgreSQL 18 StatefulSet + `db-migrate` Flyway Job)
   - `minio` (Chainguard MinIO StatefulSet + `minio-init` Job)
   - `ftp` (`filegrid/vsftpd:local` StatefulSet): the `ftp` Service exposes control port `21` and passive ports `21100-21119`. Kubernetes injects `$(FTP_SERVICE_HOST)` (the Service's fixed `ClusterIP`) into the pod, and `FTP_PASV_ADDRESS="$(FTP_SERVICE_HOST)"` makes `vsftpd` advertise that fixed cluster address in `PASV` replies so passive connections work across pod restarts.
3. **Network isolation (`k8s/network-policies.yaml`)**:
   - `postgres-isolation`, `minio-isolation`, and `ftp-isolation` restrict storage ingress exclusively to `app.kubernetes.io/component: backend` pods (plus `keycloak` for its DB and `blackbox` for reachability probes).
   - `backend-access` restricts actuator port `8081` exclusively to `prometheus`.
4. **Single TLS entry point & observability (`k8s/ingress.yaml`, `k8s/observability.yaml`)**:
   - Ingress terminates TLS (`secretName: filegrid-tls`) and routes `/api/v1/meter-readings`, `/api/v1/fault-records`, `/api/v1/drone-videos`, `/api`, `/auth`, `/grafana`, `/mailpit`, and `/`.
   - Prometheus discovers all backend replicas via the `backend-metrics` headless Service (`dns_sd_configs` on port `8081`).

## How to create secrets

No secret values are stored in the repository. Generate `.env` (if not already present) and create the `filegrid-secrets` and `filegrid-tls` Kubernetes `Secret`s:

```sh
./k8s/create-secrets.sh
```

## How to deploy

1. Build local images and load them into your cluster (e.g. `kind`):
   ```sh
   docker compose build backend ftp frontend
   kind load docker-image filegrid/backend:local filegrid/vsftpd:local filegrid/frontend:local
   ```
2. Create secrets and apply the Kustomize overlay (with `--load-restrictor=LoadRestrictionsNone` so Kustomize can package the shared SQL migrations and observability configs from `../db` and `../infra`):
   ```sh
   ./k8s/create-secrets.sh
   kubectl kustomize k8s --load-restrictor=LoadRestrictionsNone | kubectl apply -f -
   ```

## Static validation (without a cluster)

Validate the Kustomize build and check every resource against official Kubernetes JSON schemas in a single container command:

```sh
docker run --rm -v "$PWD":/repo -w /repo alpine/k8s:1.32.2 \
  kustomize build --load-restrictor=LoadRestrictionsNone k8s \
  | docker run --rm -i ghcr.io/yannh/kubeconform:v0.6.7-alpine -strict -summary
```

## How to run the scaling & resilience proof

Once deployed to a cluster with `metrics-server` and `ingress-nginx` (port-forwarded or bound to `https://localhost:8443`):

1. **Seed meters and baseline state**:
   ```sh
   kubectl -n filegrid get hpa,deploy
   make seed SIM_ARGS=--simulator.meters.count=1000
   ```
2. **Drive a 10x storm spike on meters and fault records**:
   ```sh
   make gen-storm
   make seed-data DATA_ARGS="--workers 8" &
   SIMULATOR_SINK_URL=https://localhost:8443/api/v1/meter-readings make sim SIM_ARGS="--spring.profiles.active=peak --simulator.meters.count=1000"
   ```
3. **Observe category isolation and HPA scaling**:
   - Watch `kubectl -n filegrid get hpa -w`: `backend-meters`, `meter-processor`, and `backend-faults` scale up while `backend-general` and `backend-videos` remain at baseline replicas.
   - Check `filegrid-overview` in Grafana (`/grafana/d/filegrid-overview`): latency of `CONNECTION_DOCUMENTS` and `DRONE_VIDEO` stays flat during the `METER_READING` and `FAULT_RECORD` burst.
4. **Storage pod fault tolerance**:
   ```sh
   kubectl -n filegrid scale statefulset/ftp --replicas=0
   ```
   - `POST /api/v1/fault-records` (when routed to FTP) returns `503 storage_unavailable`; connection requests (`S3`) and meter readings (`DATABASE`) continue unaffected; `StorageBackendDown` fires in Prometheus/Alertmanager.
   - Restore FTP: `kubectl -n filegrid scale statefulset/ftp --replicas=1`.
