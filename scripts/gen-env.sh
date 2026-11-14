#!/bin/sh
# Creates .env from .env.example, replacing every __GENERATE__ with a random secret.
# Refuses to overwrite an existing .env (the database volume was initialised with it).
set -eu

cd "$(dirname "$0")/.."

if [ -e .env ]; then
  # Backfill any newly added keys from .env.example without touching existing secrets.
  added=0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|\#*) continue ;;
      *=*)
        key="${line%%=*}"
        if ! grep -q "^${key}=" .env; then
          case "$line" in
            *=__GENERATE__) printf '%s=%s\n' "$key" "$(openssl rand -hex 32)" >> .env ;;
            *) printf '%s\n' "$line" >> .env ;;
          esac
          added=$((added + 1))
        fi
        ;;
    esac
  done < .env.example
  if [ "$added" -gt 0 ]; then
    echo "Appended $added missing variable(s) from .env.example to existing .env."
    exit 0
  fi
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
