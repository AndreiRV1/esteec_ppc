#!/bin/sh
# Brings an existing realm up to date with what the import file adds for registration
# (the import only runs when the realm does not exist yet). Idempotent; secrets stay in
# the keycloak container's environment.
#   - client `filegrid-backend` (confidential, service account) with the current secret
#   - its service account may manage users (create applicant logins), via the admin API scope
set -eu
cd "$(dirname "$0")/.."
docker compose --env-file "${FILEGRID_ENV_FILE:-.env}" exec -T keycloak bash -c '
set -eu
kc() { /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm-sync.config; }
trap "rm -f /tmp/kcadm-sync.config" EXIT
# Right after start Keycloak can answer 503 for a few seconds although healthy.
for attempt in $(seq 30); do
  kc config credentials --server "http://localhost:8080${KC_HTTP_RELATIVE_PATH:-}" --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 && break
  [ "$attempt" -lt 30 ] || { echo "keycloak: admin login failed" >&2; exit 1; }
  sleep 2
done
id=$(kc get clients -r filegrid -q clientId=filegrid-backend --fields id --format csv --noquotes)
if [ -z "$id" ]; then
  kc create clients -r filegrid -s clientId=filegrid-backend -s publicClient=false \
    -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s "secret=$FILEGRID_KEYCLOAK_BACKEND_SECRET" >/dev/null
  echo "keycloak: created client filegrid-backend"
else
  kc update "clients/$id" -r filegrid -s "secret=$FILEGRID_KEYCLOAK_BACKEND_SECRET"
fi
id=$(kc get clients -r filegrid -q clientId=filegrid-backend --fields id --format csv --noquotes)
# Keycloak 26.x admin API only accepts tokens that carry the kc-admin-api scope.
scope=$(kc get client-scopes -r filegrid --fields id,name --format csv --noquotes | grep ",kc-admin-api$" | cut -d, -f1)
kc update "clients/$id/default-client-scopes/$scope" -r filegrid
kc add-roles -r filegrid --uusername service-account-filegrid-backend --cclientid realm-management --rolename manage-users
'
