#!/bin/sh
# Prepares a running stack (`make up`) for the showcase in docs/demo.md:
#   1. the meter fleet, and client logins for some meters (db/seed/clients.csv)
#   2. two days of consumption history, simulated fast (the always-on simulator then continues live)
#   3. a few fault records and drone videos, uploaded through the API
#   4. the logins to use, printed at the end
# Safe to re-run: everything is idempotent or adds a little more data.
set -eu
cd "$(dirname "$0")/.."
ENV_FILE="${FILEGRID_ENV_FILE:-.env}"
export FILEGRID_ENV_FILE="$ENV_FILE"
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
value() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1; }

step "1. Meters and client logins"
make -s seed >/dev/null
make -s seed-clients CLIENTS="${CLIENTS:-2}"

step "2. Two days of consumption history (about a minute)"
proj=${COMPOSE_PROJECT_NAME:-filegrid}
# allow-future is off by default: the clock never runs past now, so stopping it late is harmless.
timeout "${HISTORY_SECONDS:-75}" make -s sim SIM_ARGS="--simulator.clock.start=-P2D --simulator.clock.speed=4000 \
  --simulator.reporting.batch-min=4 --simulator.reporting.batch-max=8" >/dev/null 2>&1 || true
docker rm -f "$proj-meter-simulator" >/dev/null 2>&1 || true
echo "History loaded; the simulator service keeps reporting live."

step "3. Fault records and drone videos"
python3 backend/fault_videos/fault_videos.py generate --faults-per-region 3 --videos-per-region 1 \
  --fault-max-mb 2 --video-size-mb 30 >/dev/null
python3 backend/fault_videos/fault_videos.py ingest | tail -4

step "4. Logins for the demo"
url=$(value FILEGRID_PUBLIC_URL); url=${url:-https://localhost:8443}
client=$(sed -n 2p db/seed/clients.csv)
cat <<TXT
  App          $url/
  Grafana      $url/grafana/
  Admin        admin@filegrid.local / $(value FILEGRID_ADMIN_PASSWORD)
  Employee     employee.demo@filegrid.local / $(value FILEGRID_DEMO_EMPLOYEE_PASSWORD)
  Client       client.demo@filegrid.local / $(value FILEGRID_DEMO_CLIENT_PASSWORD)
  Client (id)  $(echo "$client" | cut -d, -f1) / $(echo "$client" | cut -d, -f2)   (all: db/seed/clients.csv)
  Documents    demo/documents/*.pdf   Fault file: demo/faults/avarie-voluntari.json
  Drone video  $(ls backend/fault_videos/data/videos/*/*.mp4 2>/dev/null | head -1)
Walkthrough: docs/demo.md
TXT
