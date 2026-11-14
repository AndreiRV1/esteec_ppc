#!/bin/sh
# One-shot MinIO bootstrap; safe to re-run (every step is idempotent).
set -eu

: "${MINIO_ROOT_USER:?required}"
: "${MINIO_ROOT_PASSWORD:?required}"
: "${FILEGRID_S3_ACCESS_KEY:?required}"
: "${FILEGRID_S3_SECRET_KEY:?required}"

export MC_CONFIG_DIR=/tmp/.mc
# Credentials via env instead of `mc alias set`, so nothing is written to disk.
export MC_HOST_fg="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000"

# One bucket per file category. Meter readings live in PostgreSQL.
BUCKETS="filegrid-fault-records filegrid-connection-documents filegrid-drone-videos"

echo "waiting for minio..."
i=0
until mc ready fg >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && { echo "minio not ready after 60s" >&2; exit 1; }
  sleep 1
done

for b in $BUCKETS; do
  mc mb --ignore-existing "fg/$b"
  # Versioning: an overwrite or delete never destroys a confirmed file.
  mc version enable "fg/$b"
  mc anonymous set none "fg/$b"
done

mc admin policy create fg filegrid-app /init/filegrid-app-policy.json
mc admin user add fg "$FILEGRID_S3_ACCESS_KEY" "$FILEGRID_S3_SECRET_KEY"
# `attach` fails if the policy is already attached; that's the only error we tolerate.
if ! out=$(mc admin policy attach fg filegrid-app --user "$FILEGRID_S3_ACCESS_KEY" 2>&1); then
  case "$out" in
    *already*) ;;
    *) echo "$out" >&2; exit 1 ;;
  esac
fi

echo "minio bootstrap complete: buckets [$BUCKETS], user $FILEGRID_S3_ACCESS_KEY"
