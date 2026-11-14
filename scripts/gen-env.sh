#!/bin/sh
# Creates .env from .env.example, generating every secret:
#   __GENERATE__        -> 48 random hex characters
#   __GENERATE_KEY32__  -> 32 random bytes, base64 (AES-256 master keys)
# If .env already exists, only variables missing from it are appended, so
# existing secrets (which the data volumes were initialised with) never change.
set -eu

cd "$(dirname "$0")/.."

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
