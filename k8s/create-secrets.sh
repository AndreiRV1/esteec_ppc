#!/bin/sh
# Creates the Kubernetes Secrets required by the FileGrid manifests in namespace
# `filegrid` without storing secret values in Git.
#
# Usage:
#   ./k8s/create-secrets.sh              # reads ./.env (creates it via gen-env.sh if missing)
#   FILEGRID_ENV_FILE=/path/to/env ./k8s/create-secrets.sh
set -eu

cd "$(dirname "$0")/.."
ENV_FILE="${FILEGRID_ENV_FILE:-./.env}"
NAMESPACE="${FILEGRID_K8S_NAMESPACE:-filegrid}"

if [ ! -f "$ENV_FILE" ]; then
  FILEGRID_ENV_FILE="$ENV_FILE" ./scripts/gen-env.sh
fi

for tool in kubectl openssl; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

kubectl -n "$NAMESPACE" create secret generic filegrid-secrets \
  --from-env-file="$ENV_FILE" \
  --dry-run=client -o yaml | kubectl apply -f -

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

HOST=$(sed -n 's|^FILEGRID_PUBLIC_URL=https\?://\([^:/]*\).*|\1|p' "$ENV_FILE")
HOST="${HOST:-localhost}"

openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout "$WORK/tls.key" -out "$WORK/tls.crt" \
  -subj "/CN=$HOST/O=FileGrid PPC" \
  -addext "subjectAltName=DNS:$HOST,DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1

kubectl -n "$NAMESPACE" create secret tls filegrid-tls \
  --cert="$WORK/tls.crt" --key="$WORK/tls.key" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secrets filegrid-secrets and filegrid-tls applied in namespace $NAMESPACE."
