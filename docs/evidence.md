# Evidence: the spec's §4 "show live" items, measured

The storm and Kubernetes rows were measured on a laptop (Apple Silicon, Docker Desktop with 3.8 GB RAM and 8 CPUs, one machine for everything:
storages, backend, Keycloak, observability, simulated meters). Numbers depend on the machine.
Rerun them with the commands shown.

| §4 item | Command | Result | Status |
|---|---|---|---|
| 10x spike in one category, others unaffected | `make demo-storm` | 300 fault files (800 MB) in 18 s, 0 failed; other categories: 0 errors, p95 ≤ 0.8 s | measured |
| Scaling by instances | `make demo-storm REPLICAS=2` | both replicas `up` in Prometheus, both serve traffic, no reading processed twice | measured (compose) |
| Scaling per category (HPA) | `k8s/README.md` | manifests deploy on kind; HPA run not finished | partly verified |
| Degraded link (latency, loss, outage) | `make demo-degraded` | 100 ms, 1 % loss, 20 s outage mid-upload: resumed by itself, 3/3 SHA-256 match | measured in an earlier run |
| Corruption detected on retrieval | `make demo-corruption` | download refused (`integrity_check_failed`), file marked `CORRUPTED`, alert raised | measured in an earlier run |
| Files > 1 GB | `make gen-large-video && make seed-data` | resumable upload in parts; backend memory 353 → 392 MiB during the upload | measured in an earlier run |
| Storage unavailable | `docker compose stop ftp` (docs/demo.md step 9) | FTP categories → 503 `storage_unavailable`; racordare and meters keep working; `StorageBackendDown` alert + e-mail | measured in an earlier run |

## 10x storm on fault records (`scripts/demo-storm.sh`)

The storm uploads 300 fault files (1-4 MB each, 100 per region) with 8 parallel workers, while
2000 simulated meters keep reporting. Every 0.5 s it probes one request in each of the other categories.

| | 1 backend instance | 2 backend instances |
|---|---|---|
| Storm throughput | 300 files, 800 MB in 17.8 s (45 MB/s, 16.9 files/s), 0 failed, 0 retries | 300 files, 787 MB in 23.7 s (33 MB/s, 12.7 files/s), 0 failed, 0 retries |
| `CONNECTION_DOCUMENTS` p50/p95, baseline → storm | 18/48 → 89/778 ms | 19/39 → 140/467 ms |
| `DRONE_VIDEO` p50/p95, baseline → storm | 12/33 → 78/244 ms | 15/65 → 155/416 ms |
| `METER_READING` p50/p95, baseline → storm | 13/44 → 59/367 ms | 15/40 → 159/443 ms |
| Errors in the other categories | 0 | 0 |
| Meter messages stored per minute, before / during | 188 / 189 | 206 / 209 |

How to read it:
- The storm costs the other categories latency, but no errors, and meter ingest keeps its rate.
- A second instance does not help on one laptop. Both replicas share the same CPUs, Postgres and storage,
  so they compete for them. Real capacity comes from spreading replicas across nodes (Kubernetes below).

Two replicas, checked after the run:
- Prometheus `up`: `backend 172.21.0.10:8081 = 1` and `backend 172.21.0.11:8081 = 1`.
  Requests in 5 min: 1433 and 330, so both replicas served traffic.
- No reading was processed twice:
  - In 15 min, 2998 raw messages announced 4443 interval values. 4385 rows were created; the difference
    is the values of the rejected messages.
  - `(meter_id, interval_start)` has 0 duplicates. It is unique by constraint, and the processor claims
    raw rows with `FOR UPDATE SKIP LOCKED`.
  - 0 messages were still pending after 1 min.

## Kubernetes (kind, single node)

Steps from `k8s/README.md`:
1. `kind create cluster`, with port 443 on `127.0.0.1:8443`.
2. ingress-nginx v1.13.3 (kind provider), and metrics-server v0.8.0 with `--kubelet-insecure-tls`.
3. `kind load docker-image filegrid/backend:local filegrid/vsftpd:local filegrid/frontend:local`.
4. `./k8s/create-secrets.sh`.
5. `kubectl kustomize k8s --load-restrictor=LoadRestrictionsNone | kubectl apply -f -`.

The laptop's 3.8 GB cannot hold the requested 10 backend pods at 384 Mi each. The run used a local
overlay instead:
- 1 minimum replica per category (HPA maximums unchanged) and 200 Mi requests.
- Without Loki, Tempo, Grafana, Alertmanager and Mailpit.

Result:
- Everything started: postgres, minio, ftp, keycloak, prometheus, blackbox, frontend, the 5 backend
  Deployments and the 5 HPAs. `db-migrate` and `minio-init` completed.
- **Bug found and fixed:** each backend pod opened 20 DB connections (Hikari default) and Postgres allows
  100. Five pods already used 93, and the real minimum of 10 pods could not have started.
  - `k8s/backend.yaml` now sets `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5`.
  - `k8s/storages.yaml` sets `max_connections=300`: 40 pods at the HPA maximums × 5, plus Keycloak.
- **Not done:**
  - The `peak` load and the HPA before/after replica counts. The run was stopped because the laptop
    overheated.
  - Needs a machine with ≥ 8 GB for Docker. Follow "How to run the scaling & resilience proof" in
    `k8s/README.md`.
- kind's default CNI does not enforce NetworkPolicies. Checking `k8s/network-policies.yaml` needs a CNI
  that does, such as Calico or Cilium.
