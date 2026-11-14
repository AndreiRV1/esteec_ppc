#!/bin/sh
# Creates .env from .env.example, generating every secret:
#   __GENERATE__        -> 48 random hex characters
#   __GENERATE_KEY32__  -> 32 random bytes, base64 (AES-256 master keys)
# If .env already exists, only variables missing from it are appended, so
# existing secrets (which the data volumes were initialised with) never change.
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

value_for() {
  case "$1" in
    __GENERATE__) openssl rand -hex 24 ;;
    __GENERATE_KEY32__) openssl rand -base64 32 ;;
    *) printf '%s' "$1" ;;
  esac
}

umask 077
tmp=$(mktemp .env.XXXXXX)
trap 'rm -f "$tmp"' EXIT

if [ -e .env ]; then
  cp .env "$tmp"
  added=0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*) continue ;;
    esac
    key=${line%%=*}
    if ! grep -q "^${key}=" .env; then
      printf '%s=%s\n' "$key" "$(value_for "${line#*=}")" >> "$tmp"
      echo "added $key"
      added=$((added + 1))
    fi
  done < .env.example
  mv "$tmp" .env
  trap - EXIT
  echo ".env is up to date ($added new variable(s))."
  exit 0
fi

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) printf '%s\n' "$line" ;;
    *) printf '%s=%s\n' "${line%%=*}" "$(value_for "${line#*=}")" ;;
  esac
done < .env.example > "$tmp"

mv "$tmp" .env
trap - EXIT
echo "Wrote .env with freshly generated secrets (mode 600)."
