#!/bin/bash
# A 10x storm in one category (fault records) must not degrade the others; capacity grows by adding
# instances. Measures, against the running stack:
#   phase 1  baseline: latency of the other categories (racordare, drone videos, customer consumption)
#   phase 2  the same probes while ~300 fault files (1-4 MB) are uploaded by 8 parallel workers,
#            on top of the simulated meters that keep reporting
# and prints p50/p95 per probe for both phases plus the storm's own throughput.
#   REPLICAS=2 scripts/demo-storm.sh    runs it with two backend instances (docker compose --scale)
# Needs a running stack (`make up`, `make demo-setup` for the client logins), curl, jq, python3.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; ENV_FILE="${FILEGRID_ENV_FILE:-.env}"; . "./${ENV_FILE#./}"; set +a
BASE="${FILEGRID_PUBLIC_URL:-https://localhost:8443}"; API="$BASE/api/v1"
COMPOSE="docker compose --env-file $ENV_FILE"
REPLICAS="${REPLICAS:-1}"; BASELINE_SECONDS="${BASELINE_SECONDS:-30}"; FAULTS_PER_REGION="${FAULTS_PER_REGION:-100}"
DATA=backend/fault_videos/data-storm
WORK=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"' EXIT
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
token() { curl -sk "$BASE/auth/realms/filegrid/protocol/openid-connect/token" -d grant_type=password -d client_id=filegrid-cli \
  --data-urlencode "username=$1" --data-urlencode "password=$2" | jq -r .access_token; }

step "Backend instances: $REPLICAS"
$COMPOSE up -d --no-deps --no-recreate --wait --scale backend="$REPLICAS" backend >/dev/null 2>&1
sleep 6 # the proxy re-resolves the backend DNS name every 5 s
echo "running: $($COMPOSE ps backend --format '{{.Name}}' | tr '\n' ' ')"

step "Storm files: $FAULTS_PER_REGION fault records per region"
rm -rf "$DATA"
python3 backend/fault_videos/fault_videos.py --data-dir "$DATA" generate --profile storm \
  --faults-per-region "$FAULTS_PER_REGION" --videos-per-region 0 --fault-min-mb 1 --fault-max-mb 4 >/dev/null 2>&1
echo "$(find "$DATA/faults" -type f -name '*.json' | wc -l) files, $(du -sm "$DATA/faults" | cut -f1) MB"

CLIENT_USER=$(sed -n 2p db/seed/clients.csv | cut -d, -f1); CLIENT_PASS=$(sed -n 2p db/seed/clients.csv | cut -d, -f2)
CLIENT=$(token "$CLIENT_USER" "$CLIENT_PASS")
EMPLOYEE=$(token employee.demo@filegrid.local "$FILEGRID_DEMO_EMPLOYEE_PASSWORD")

# One probe = one request per category, every 0.5 s; each line: "<phase> <category> <ms> <http code>".
probe() {
  local phase=$1 until=$2
  while [ "$(date +%s)" -lt "$until" ] && [ ! -e "$WORK/stop-$phase" ]; do
    for spec in "CONNECTION_DOCUMENTS|$CLIENT|/connection-requests" "METER_READING|$CLIENT|/me/consumption/summary" \
                "DRONE_VIDEO|$EMPLOYEE|/drone-videos?size=5"; do
      IFS='|' read -r cat tok path <<<"$spec"
      curl -sk -o /dev/null -w "$phase $cat %{time_total} %{http_code}\n" -H "Authorization: Bearer $tok" "$API$path" >> "$WORK/probes"
    done
    sleep 0.5
  done
}

step "Phase 1: baseline (${BASELINE_SECONDS}s)"
probe baseline $(( $(date +%s) + BASELINE_SECONDS ))
meters_before=$($COMPOSE exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM filegrid.meter_reading_raw WHERE received_at > now() - interval '"'"'1 minute'"'"'"')

step "Phase 2: storm on fault records + the same probes"
probe storm $(( $(date +%s) + 900 )) &
start=$(date +%s)
python3 backend/fault_videos/fault_videos.py --data-dir "$DATA" ingest --category FAULT_RECORD --workers 8 2>&1 \
  | grep -E '^(CATEGORY|FAULT_RECORD|Total)' || true
elapsed=$(( $(date +%s) - start )); touch "$WORK/stop-storm"; wait
meters_during=$($COMPOSE exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM filegrid.meter_reading_raw WHERE received_at > now() - interval '"'"'1 minute'"'"'"')

step "Result ($REPLICAS backend instance(s)): latency of the OTHER categories"
python3 - "$WORK/probes" <<'PY'
import sys, collections
data = collections.defaultdict(list); errors = collections.Counter()
for line in open(sys.argv[1]):
    phase, cat, seconds, code = line.split()
    data[(cat, phase)].append(float(seconds) * 1000)
    if not code.startswith("2"): errors[(cat, phase)] += 1
def pct(values, q):
    values = sorted(values); return values[min(len(values) - 1, int(q * len(values)))]
print(f"{'category':22} {'baseline p50/p95 (ms)':>24} {'storm p50/p95 (ms)':>22} {'errors':>8}")
for cat in ("CONNECTION_DOCUMENTS", "DRONE_VIDEO", "METER_READING"):
    b, s = data[(cat, "baseline")], data[(cat, "storm")]
    if not b or not s: continue
    print(f"{cat:22} {pct(b,.5):11.0f} / {pct(b,.95):<10.0f} {pct(s,.5):10.0f} / {pct(s,.95):<9.0f} {errors[(cat,'storm')]:>6}")
PY
echo "storm: fault uploads took ${elapsed}s; meter messages stored per minute: ${meters_before} before, ${meters_during} during"
