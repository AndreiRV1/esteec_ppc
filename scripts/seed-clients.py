#!/usr/bin/env python3
"""Creates demo customer accounts for meters that are already in the database.

For N meters per region (default 5) that have no account yet:
  * a Keycloak user in realm `filegrid`, role `client`, username = client id
    (`cl-<meter number>`), with a random password;
  * a row in filegrid.customer_account linking that username to the meter.
The credentials are appended to db/seed/clients.csv (git-ignored, mode 600).

Needs the stack to be running (`make up` or `make dev`) and meters to be loaded
(`make seed`). Only Docker and Python 3 are required: it talks to PostgreSQL and
Keycloak through `docker compose exec`, so nothing has to be exposed.
"""
import csv
import json
import os
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "db" / "seed" / "clients.csv"
COMPOSE = ["docker", "compose"] + (
    ["--env-file", os.environ["FILEGRID_ENV_FILE"]] if os.environ.get("FILEGRID_ENV_FILE") else []
)


DEMO_CLIENT = "client.demo@filegrid.local"


def run(service, command, stdin=None):
    """Runs a shell command inside a service container and returns its stdout."""
    result = subprocess.run(COMPOSE + ["exec", "-T", service, "sh", "-c", command],
                            input=stdin, capture_output=True, text=True, cwd=ROOT)
    if result.returncode != 0:
        sys.exit(f"{service}: command failed\n{result.stdout}{result.stderr}")
    return result.stdout


def psql(sql):
    return run("postgres", 'PGPASSWORD="$FILEGRID_DB_MIGRATOR_PASSWORD" psql -q -v ON_ERROR_STOP=1 -tA -F "|" '
                           '-h 127.0.0.1 -U filegrid_migrator -d "$POSTGRES_DB"', stdin=sql)


def main():
    per_region = int(sys.argv[1]) if len(sys.argv) > 1 else 5

    rows = psql(f"""
        SELECT id, region, county, locality FROM (
            SELECT m.*, row_number() OVER (PARTITION BY m.region ORDER BY m.id) AS rn
            FROM filegrid.meter m
            WHERE m.active AND NOT EXISTS (SELECT 1 FROM filegrid.customer_account a WHERE a.meter_id = m.id)
        ) free WHERE rn <= {per_region} ORDER BY region, id;
    """).strip().splitlines()
    if not rows:
        sys.exit("No meters without an account found. Load meters first: make seed")

    clients = []
    for row in rows:
        meter_id, region, county, locality = row.split("|")
        username = "cl-" + meter_id.rsplit("-", 1)[-1]
        clients.append({"username": username, "password": secrets.token_urlsafe(15), "meter_id": meter_id,
                        "region": region, "county": county, "locality": locality})

    # Keycloak: one partial import, run with the admin CLI inside the container.
    users = [{
        "username": c["username"], "enabled": True, "emailVerified": True,
        "email": f"{c['username']}@clients.filegrid.local", "firstName": "Client", "lastName": c["username"].upper(),
        "credentials": [{"type": "password", "value": c["password"], "temporary": False}],
        "realmRoles": ["client"],
    } for c in clients]
    kcadm = "/opt/keycloak/bin/kcadm.sh"
    run("keycloak",
        f'{kcadm} config credentials --config /tmp/kcadm.config '
        f'--server "http://localhost:8080${{KC_HTTP_RELATIVE_PATH:-}}" --realm master '
        f'--user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null && '
        f'{kcadm} create partialImport --config /tmp/kcadm.config -r filegrid -o -f - >/dev/null; '
        f'status=$?; rm -f /tmp/kcadm.config; exit $status',
        stdin=json.dumps({"ifResourceExists": "OVERWRITE", "users": users}))

    values = ",\n".join(f"('{c['username']}', '{c['region']}', '{c['meter_id']}')" for c in clients)
    psql(f"INSERT INTO filegrid.customer_account (username, region, meter_id) VALUES\n{values}\n"
         "ON CONFLICT DO NOTHING;")
    # The realm's own demo client gets a meter too, so its portal shows data (no-op once linked).
    psql(f"INSERT INTO filegrid.customer_account (username, region, meter_id) "
         f"SELECT '{DEMO_CLIENT}', m.region, m.id FROM filegrid.meter m WHERE m.active AND NOT EXISTS "
         "(SELECT 1 FROM filegrid.customer_account a WHERE a.meter_id = m.id) ORDER BY m.id LIMIT 1 "
         "ON CONFLICT DO NOTHING;")

    # One row per login, the latest password wins (the import above resets passwords of existing logins).
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fields = ["username", "password", "meter_id", "region", "county", "locality"]
    rows = {}
    if OUT.exists():
        with open(OUT, newline="", encoding="utf-8") as f:
            rows = {r["username"]: r for r in csv.DictReader(f)}
    rows.update({c["username"]: c for c in clients})
    # Drop logins whose account is gone (e.g. after the database was reset).
    existing = set(psql("SELECT username FROM filegrid.customer_account;").split())
    rows = {u: r for u, r in rows.items() if u in existing}
    with open(os.open(OUT, os.O_WRONLY | os.O_TRUNC | os.O_CREAT, 0o600), "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(sorted(rows.values(), key=lambda r: r["username"]))
    print(f"Created {len(clients)} customer accounts. Credentials: {OUT.relative_to(ROOT)}")
    print(f"Example login: {clients[0]['username']} (meter {clients[0]['meter_id']}, {clients[0]['county']})")


if __name__ == "__main__":
    main()
