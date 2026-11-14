#!/bin/sh
# Runs once, when the data volume is empty. Keycloak gets its own role and
# database: it never touches the `filegrid` database, and FileGrid roles
# cannot connect to Keycloak's.
set -eu

: "${KEYCLOAK_DB_PASSWORD:?required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL
