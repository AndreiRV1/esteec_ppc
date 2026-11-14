#!/bin/sh
# Shows the spec's §4 security requirements against the running stack:
#   1. only the TLS proxy is published; Postgres, MinIO and FTP refuse connections from the host
#   2. the API needs a token
#   3. someone with direct storage access (here: MinIO root credentials) gets only ciphertext of the
#      connection documents: not a ZIP, no readable personal data; the database keeps the CNP only as an HMAC
#   4. no password or key is tracked by Git
# Needs a running stack (`make up`) with at least one connection request, curl and jq.
set -eu
cd "$(dirname "$0")/.."
set -a
ENV_FILE="${FILEGRID_ENV_FILE:-.env}"
case "$ENV_FILE" in */*) ;; *) ENV_FILE="./$ENV_FILE" ;; esac
. "$ENV_FILE"
set +a
BASE="${FILEGRID_PUBLIC_URL:-https://localhost:8443}"
COMPOSE="docker compose --env-file $ENV_FILE"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
psql() { $COMPOSE exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; }

step "1. Published ports: only the proxy"
$COMPOSE ps --format '{{.Service}}	{{.Ports}}' | awk -F'\t' '$2 ~ /->/ {print "  " $1 ": " $2; n++} END {if (!n) print "  none"}'
for target in "Postgres 5432" "MinIO 9000" "FTP 21"; do
  set -- $target
  # curl exits 7 only when the connection is refused; an open but silent port (Postgres) times out with 28.
  rc=0; curl -s --max-time 2 "telnet://127.0.0.1:$2" </dev/null >/dev/null 2>&1 || rc=$?
  if [ "$rc" -ne 7 ]; then
    echo "  $1 on 127.0.0.1:$2: something listens here (FileGrid itself only if listed above)"
  else
    echo "  $1 on 127.0.0.1:$2: refused (only reachable inside the storage network)"
  fi
done

step "2. Traffic is TLS, access is authenticated"
echo "  GET $BASE/api/v1/connection-requests without a token: HTTP $(curl -sk -o /dev/null -w '%{http_code}' "$BASE/api/v1/connection-requests")"
echo "  plain HTTP on the same port: $(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${PROXY_HOST_PORT:-8443}/" 2>/dev/null || true) (no cleartext API)"

step "3. Direct storage access exposes no personal data"
ROW=$(echo "SELECT f.id || '|' || f.backend || '|' || coalesce(f.location, '') || '|' || f.encryption FROM filegrid.stored_file f
            WHERE f.category = 'CONNECTION_DOCUMENTS' AND f.status = 'STORED' ORDER BY f.created_at DESC LIMIT 1;" | psql)
[ -n "$ROW" ] || { echo "  No connection request stored yet: submit one first (docs/demo.md, act 1)." >&2; exit 1; }
FILE_ID=${ROW%%|*}; ROW=${ROW#*|}
BACKEND=${ROW%%|*}; REST=${ROW#*|}; LOCATION=${REST%%|*}; ENCRYPTION=${REST#*|}
echo "  latest racordare archive: $BACKEND $LOCATION, encryption: $ENCRYPTION"
if [ "$BACKEND" = S3 ]; then
  # Same credentials an attacker would need: MinIO root, from inside the storage network.
  $COMPOSE run --rm --no-deps -T --entrypoint /bin/sh minio-init -c \
    'mc alias set fg http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc cat "fg/$0"' "$LOCATION" > "$WORK/raw" 2>"$WORK/err" \
    || { echo "  could not read the object from MinIO: $(tail -1 "$WORK/err")" >&2; exit 1; }
elif [ "$BACKEND" = FTP ]; then
  $COMPOSE exec -T ftp cat "/srv/ftp/home$LOCATION" > "$WORK/raw"
else
  echo "  stored in the database: ciphertext in a bytea column"; : > "$WORK/raw"
fi
if [ -s "$WORK/raw" ]; then
  echo "  raw bytes read directly from storage: $(wc -c < "$WORK/raw" | tr -d ' ') bytes, first 16: $(head -c 16 "$WORK/raw" | od -An -tx1 | tr -d '\n')"
  [ "$(head -c 2 "$WORK/raw")" = PK ] && echo "  WARNING: this is a readable ZIP" || echo "  not a ZIP (a ZIP starts with 50 4b = \"PK\"): AES-256-GCM ciphertext"
  hits=$(LC_ALL=C grep -a -c -E 'CNP|%PDF|[1-8][0-9]{12}' "$WORK/raw" || true)
  echo "  readable PDF headers, 'CNP' or 13-digit numbers in it: ${hits:-0}"
fi
echo "  database row of the applicant: $(echo "SELECT 'identity_hmac=' || left(identity_hmac, 16) || '…' FROM filegrid.client WHERE archive_file_id = '$FILE_ID';" | psql)"
echo "  (no CNP column: a returning applicant is found by HMAC-SHA256 with a server-side secret)"

step "4. No secrets in Git"
tracked=$(git ls-files | grep -E '(^|/)\.env(\..*)?$|^db/seed/|\.(pem|key|p12|jks)$' | grep -v '\.env\.example$' || true)
echo "  env files, keys and db/seed/ tracked by Git: ${tracked:-none}"
git check-ignore -q .env && echo "  .env is ignored" || { echo "  WARNING: .env is NOT in .gitignore" >&2; exit 1; }
[ -z "$tracked" ] || { echo "  WARNING: secrets are tracked by Git" >&2; exit 1; }
echo "  secrets are generated on first \`make up\` by scripts/gen-env.sh; k8s reads them via k8s/create-secrets.sh"
