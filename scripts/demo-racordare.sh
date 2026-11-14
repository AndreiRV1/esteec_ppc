#!/bin/sh
# End-to-end racordare walkthrough against a running stack (`make up`):
#   1. the demo client logs in (Keycloak) and submits cerere + CI + aviz
#   2. an admin logs in, reviews the request, downloads and verifies the archive
#   3. the admin accepts it and the client sees the new status
# Uses the seeded users from .env. Data is synthetic (the CNP is a valid test number).
set -eu

cd "$(dirname "$0")/.."
for tool in curl jq shasum; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
set -a
. ./.env
set +a

API="http://localhost:${BACKEND_HOST_PORT:-8080}/api/v1"
TOKEN_URL="http://localhost:${KEYCLOAK_HOST_PORT:-8180}/realms/filegrid/protocol/openid-connect/token"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

token() {
  curl -fsS "$TOKEN_URL" -d grant_type=password -d client_id=filegrid-cli \
    --data-urlencode "username=$1" --data-urlencode "password=$2" | jq -r .access_token
}

# Minimal but well-formed sample documents.
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%%%EOF\n' > "$WORK/cerere.pdf"
printf '\377\330\377\340\000\020JFIF\000demo-ci\377\331' > "$WORK/ci.jpg"
cp "$WORK/cerere.pdf" "$WORK/aviz.pdf"

cat > "$WORK/request.json" <<JSON
{
  "applicantType": "PERSOANA_FIZICA",
  "requestType": "LOC_CONSUM_NOU",
  "applicant": {
    "fullName": "Ion Demo",
    "identityNumber": "1960529460012",
    "email": "client.demo@filegrid.local",
    "phone": "+40 721 000 000",
    "address": "str. Exemplului nr. 1, Voluntari, Ilfov"
  },
  "consumptionPlace": { "county": "IF", "locality": "Voluntari", "address": "str. Exemplului nr. 2" },
  "requestedPowerKw": 9.5,
  "permit": {
    "type": "AVIZ_AMPLASAMENT",
    "number": "AA-$(date +%Y)-0042",
    "issuer": "Rețele Electrice Muntenia - Sucursala Ilfov",
    "issuedOn": "$(date +%Y-%m-%d)",
    "validUntil": "$(($(date +%Y) + 1))-$(date +%m-%d)"
  }
}
JSON

step "1. Client logs in and submits a connection request"
CLIENT=$(token client.demo@filegrid.local "$FILEGRID_DEMO_CLIENT_PASSWORD")
CREATED=$(curl -fsS -X POST "$API/connection-requests" -H "Authorization: Bearer $CLIENT" \
  -F "request=@$WORK/request.json;type=application/json" \
  -F "cerereRacordare=@$WORK/cerere.pdf;type=application/pdf" \
  -F "actIdentitate=@$WORK/ci.jpg;type=image/jpeg" \
  -F "aviz=@$WORK/aviz.pdf;type=application/pdf")
echo "$CREATED" | jq .
ID=$(echo "$CREATED" | jq -r .clientId)

step "2. Admin logs in and sees the PENDING queue"
ADMIN=$(token admin@filegrid.local "$FILEGRID_ADMIN_PASSWORD")
curl -fsS "$API/admin/connection-requests?status=PENDING" -H "Authorization: Bearer $ADMIN" \
  | jq '{total, items: [.items[] | {clientId, region, county, requestedPowerKw, status}]}'

step "3. Admin opens the request (personal data decrypted from the archive, access audited)"
curl -fsS "$API/admin/connection-requests/$ID" -H "Authorization: Bearer $ADMIN" \
  | jq '{applicant, permit, archive, documents: [.documents[] | {documentType, entryName, sizeBytes}]}'

step "4. Admin downloads the archive; FileGrid verified it, we check the checksum again"
curl -fsS -D "$WORK/headers" -o "$WORK/archive.zip" "$API/admin/connection-requests/$ID/archive" \
  -H "Authorization: Bearer $ADMIN"
EXPECTED=$(grep -i '^x-checksum-sha256:' "$WORK/headers" | tr -d '\r' | awk '{print $2}')
ACTUAL=$(shasum -a 256 "$WORK/archive.zip" | awk '{print $1}')
echo "server: $EXPECTED"
echo "local:  $ACTUAL"
[ "$EXPECTED" = "$ACTUAL" ] && echo "checksums match" || { echo "CHECKSUM MISMATCH" >&2; exit 1; }
unzip -l "$WORK/archive.zip" 2>/dev/null || true

step "5. Admin accepts the request"
curl -fsS -X POST "$API/admin/connection-requests/$ID/decision" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"decision":"ACCEPTED"}' | jq '{clientId, status, reviewedAt}'

step "6. Client sees the decision"
curl -fsS "$API/connection-requests/$ID" -H "Authorization: Bearer $CLIENT" | jq '{clientId, status}'
