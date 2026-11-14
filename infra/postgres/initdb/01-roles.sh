#!/bin/sh
# Runs once, when the data volume is empty (docker-entrypoint-initdb.d).
# Creates the least-privilege roles and the `filegrid` schema. Tables are
# created later by Flyway (db-migrate) as filegrid_migrator; default
# privileges below give filegrid_app DML on everything it creates.
set -eu

: "${FILEGRID_DB_MIGRATOR_PASSWORD:?required}"
: "${FILEGRID_DB_APP_PASSWORD:?required}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v db="$POSTGRES_DB" \
     -v migrator_pw="$FILEGRID_DB_MIGRATOR_PASSWORD" \
     -v app_pw="$FILEGRID_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE filegrid_migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE filegrid_app      LOGIN PASSWORD :'app_pw' CONNECTION LIMIT 200;

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO filegrid_migrator, filegrid_app;

CREATE SCHEMA filegrid AUTHORIZATION filegrid_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA filegrid TO filegrid_app;

ALTER DEFAULT PRIVILEGES FOR ROLE filegrid_migrator IN SCHEMA filegrid
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO filegrid_app;
ALTER DEFAULT PRIVILEGES FOR ROLE filegrid_migrator IN SCHEMA filegrid
    GRANT USAGE, SELECT ON SEQUENCES TO filegrid_app;

ALTER ROLE filegrid_migrator SET search_path = filegrid;
ALTER ROLE filegrid_app      SET search_path = filegrid;
-- Fail fast instead of piling up connections behind a stuck lock.
ALTER ROLE filegrid_app      SET lock_timeout = '5s';
ALTER ROLE filegrid_app      SET idle_in_transaction_session_timeout = '60s';
SQL
