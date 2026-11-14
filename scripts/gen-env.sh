#!/bin/sh
# Creates .env from .env.example, replacing every __GENERATE__ with a random secret.
# Refuses to overwrite an existing .env (the database volume was initialised with it).
set -eu

cd "$(dirname "$0")/.."

if [ -e .env ]; then
  echo ".env already exists - delete it first if you really want new secrets" >&2
  echo "(existing volumes keep the old passwords; see 'make clean')" >&2
  exit 1
fi

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

umask 077
tmp=$(mktemp .env.XXXXXX)
trap 'rm -f "$tmp"' EXIT

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *=__GENERATE__) printf '%s=%s\n' "${line%%=*}" "$(openssl rand -hex 24)" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < .env.example > "$tmp"

mv "$tmp" .env
trap - EXIT
echo "Wrote .env with freshly generated secrets (mode 600)."
