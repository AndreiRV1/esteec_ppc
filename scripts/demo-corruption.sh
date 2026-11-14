#!/bin/sh
# Shows that a corrupted file is detected on retrieval and never delivered:
#   1. the demo employee uploads a fault record (to a region whose policy is FTP)
#   2. it is downloaded through FileGrid: 200, and the SHA-256 matches the original
#   3. someone changes the file directly on the FTP server, behind FileGrid's back
#   4. the next download is refused (500 integrity_check_failed), the file is marked CORRUPTED,
#      and the FileIntegrityFailure alert reaches the on-call team (Monitorizare, Mailpit)
# Needs a running stack (`make up`), curl and jq.
set -eu
cd "$(dirname "$0")/.."
set -a
ENV_FILE="${FILEGRID_ENV_FILE:-.env}"
case "$ENV_FILE" in */*) ;; *) ENV_FILE="./$ENV_FILE" ;; esac
. "$ENV_FILE"
set +a
for tool in curl jq sha256sum; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done

BASE="${FILEGRID_PUBLIC_URL:-https://localhost:8443}"
API="$BASE/api/v1"
COMPOSE="docker compose --env-file $ENV_FILE"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TLS_OPT=""
if $COMPOSE cp proxy:/data/caddy/pki/authorities/local/root.crt "$WORK/ca.crt" >/dev/null 2>&1; then TLS_OPT="--cacert $WORK/ca.crt"; fi
curl() { command curl $TLS_OPT "$@"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
psql() { $COMPOSE exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB"' ; }

TOKEN=$(curl -fsS "$BASE/auth/realms/filegrid/protocol/openid-connect/token" -d grant_type=password -d client_id=filegrid-cli \
  --data-urlencode "username=employee.demo@filegrid.local" --data-urlencode "password=$FILEGRID_DEMO_EMPLOYEE_PASSWORD" | jq -r .access_token)

REGION=$(echo "SELECT region FROM filegrid.storage_policy WHERE category = 'FAULT_RECORD' AND backend = 'FTP' ORDER BY region LIMIT 1;" | psql)
[ -n "$REGION" ] || { echo "No region stores fault records on FTP; set one in Politici de stocare first." >&2; exit 1; }
COUNTY=$(case "$REGION" in MUNTENIA) echo IF ;; BANAT) echo TM ;; *) echo CT ;; esac)

step "1. Upload a fault record ($REGION, policy: FTP)"
printf '{"faultRecord":"integrity demo","at":"%s","samples":[1,2,3,4,5]}\n' "$(date -u +%FT%TZ)" > "$WORK/fault.json"
ORIGINAL=$(sha256sum "$WORK/fault.json" | cut -d' ' -f1)
META=$(printf '{"region":"%s","county":"%s","substationCode":"ST-INTEGRITY-DEMO","occurredAt":"%s","restoredAt":"%s","severity":"LOW","cause":"integrity demo"}' \
  "$REGION" "$COUNTY" "$(date -u +%FT%TZ)" "$(date -u +%FT%TZ)")
CREATED=$(curl -fsS -H "Authorization: Bearer $TOKEN" -F "metadata=$META;type=application/json" \
  -F "file=@$WORK/fault.json;type=application/json" "$API/fault-records")
ID=$(echo "$CREATED" | jq -r .id); FILE_ID=$(echo "$CREATED" | jq -r .fileId)
echo "record $ID, file $FILE_ID, sha256 $(echo "$CREATED" | jq -r .sha256)"

step "2. Download through FileGrid: identical to the original"
code=$(curl -sS -o "$WORK/back.json" -D "$WORK/headers" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$API/fault-records/$ID/file")
echo "HTTP $code, X-Checksum-SHA256: $(grep -i '^x-checksum-sha256' "$WORK/headers" | tr -d '\r' | cut -d' ' -f2)"
[ "$(sha256sum "$WORK/back.json" | cut -d' ' -f1)" = "$ORIGINAL" ] && echo "SHA-256 of the downloaded file = SHA-256 of the original: identical"

step "3. Change the file on the FTP server directly (outside FileGrid)"
LOCATION=$(echo "SELECT location FROM filegrid.stored_file WHERE id = '$FILE_ID';" | psql)
$COMPOSE exec -T ftp sh -c "printf 'tampered' >> '/srv/ftp/home$LOCATION'"
echo "appended 8 bytes to $LOCATION"

step "4. Download again: refused, never delivered"
code=$(curl -sS -o "$WORK/problem.json" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$API/fault-records/$ID/file")
echo "HTTP $code: $(jq -c '{code, detail, traceId}' "$WORK/problem.json")"
echo "stored_file status: $(echo "SELECT status FROM filegrid.stored_file WHERE id = '$FILE_ID';" | psql)"
echo "Within ~1 minute FileIntegrityFailure fires: Monitorizare → Alerte active, and an e-mail in $BASE/mailpit/"
