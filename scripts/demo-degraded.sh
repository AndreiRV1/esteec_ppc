#!/bin/sh
# Transfers over a slow, lossy, unstable link finish or resume without manual intervention.
#
# Runs the FileGrid uploader in a container whose network interface is degraded with netem:
#   100 ms extra latency and 1% packet loss for the whole run, plus a 20 s outage (100% loss)
#   in the middle of a drone video upload. The upload stalls, times out, retries and resumes from
#   the last part the server confirmed; then every file is downloaded again and its SHA-256 compared.
# Tune with LATENCY, JITTER, LOSS, OUTAGE_AT, OUTAGE_SECONDS, VIDEO_MB (jitter reorders packets: TCP slows
# down a lot). The uploader retries up to 30 times with backoff. Needs a running stack.
set -eu
cd "$(dirname "$0")/.."
ENV_FILE="${FILEGRID_ENV_FILE:-.env}"
PROJECT="${COMPOSE_PROJECT_NAME:-filegrid}"
LATENCY="${LATENCY:-100ms}"; JITTER="${JITTER:-0ms}"; LOSS="${LOSS:-1%}"
OUTAGE_AT="${OUTAGE_AT:-20}"; OUTAGE_SECONDS="${OUTAGE_SECONDS:-20}"; VIDEO_MB="${VIDEO_MB:-20}"
DATA=backend/fault_videos/data-degraded
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

step "1. Test files: 2 fault records and one ${VIDEO_MB} MB drone video (Muntenia)"
rm -rf "$DATA"
python3 backend/fault_videos/fault_videos.py --data-dir "$DATA" generate --regions MUNTENIA --faults-per-region 2 \
  --videos-per-region 1 --fault-min-mb 1 --fault-max-mb 1 --video-size-mb "$VIDEO_MB" >/dev/null 2>&1
docker build -q -t filegrid/degraded-client:local infra/degraded >/dev/null

step "2. Upload over the degraded link: $LATENCY ± $JITTER, $LOSS loss, ${OUTAGE_SECONDS}s outage after ${OUTAGE_AT}s"
docker run --rm --cap-add NET_ADMIN --network "${PROJECT}_edge" -v "$PWD:/repo" \
  -e FILEGRID_ENV_FILE="/repo/$ENV_FILE" -e FILEGRID_PUBLIC_URL=https://localhost:8443 -e FILEGRID_TLS_INSECURE=1 \
  -e FILEGRID_HTTP_TIMEOUT=30 -e FILEGRID_HTTP_ATTEMPTS=30 -e PYTHONDONTWRITEBYTECODE=1 \
  -e NETEM="delay $LATENCY $JITTER loss $LOSS" \
  -e OUTAGE_AT="$OUTAGE_AT" -e OUTAGE_SECONDS="$OUTAGE_SECONDS" -e DATA="$DATA" \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  filegrid/degraded-client:local sh -c '
    set -eu
    [ -n "$DATA" ] || exit 1
    # The proxy certificate is issued for "localhost": point that name at the proxy container.
    proxy_ip=$(getent hosts proxy | cut -d" " -f1)
    sed "/localhost/d" /etc/hosts > /tmp/hosts && echo "$proxy_ip localhost" >> /tmp/hosts && cat /tmp/hosts > /etc/hosts
    tc qdisc add dev eth0 root netem $NETEM
    echo "link: $(tc qdisc show dev eth0 | sed "s/.*netem [0-9a-f]*: root refcnt [0-9]* limit [0-9]* //")"
    ( sleep "$OUTAGE_AT"; tc qdisc change dev eth0 root netem loss 100%
      echo ">>> $(date +%T) link DOWN (100% packet loss)"
      sleep "$OUTAGE_SECONDS"; tc qdisc change dev eth0 root netem $NETEM
      echo ">>> $(date +%T) link back ($NETEM)" ) &
    as_user() { setpriv --reuid "$HOST_UID" --regid "$HOST_GID" --clear-groups "$@"; }
    start=$(date +%s)
    as_user python3 backend/fault_videos/fault_videos.py --data-dir "$DATA" ingest --workers 2
    echo "upload finished in $(( $(date +%s) - start ))s, without any manual step"
    wait
    echo
    echo "== 3. Download everything again (still over the bad link) and compare SHA-256"
    as_user python3 backend/fault_videos/fault_videos.py --data-dir "$DATA" verify
  ' 2>&1 | grep -v 'TLS certificates are NOT verified'
