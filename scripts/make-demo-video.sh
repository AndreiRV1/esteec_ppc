#!/bin/sh
# Generates demo/videos/inspectie-otopeni.mp4: a 4-second drone clip whose MP4 metadata carries what the
# upload form reads (creation time, duration, and the comment "region=…;county=…;line_code=…;drone_id=…;
# flight_started_at=…"). The name is neutral on purpose: the form fills from the file's metadata alone.
# Only Docker is needed: the generator and ffmpeg run in a throwaway container.
set -eu
cd "$(dirname "$0")/.."
mkdir -p demo/videos
docker run --rm -v "$PWD":/repo:ro -v "$PWD/demo/videos":/out -w /repo python:3.12-slim sh -c '
  set -e
  apt-get update -qq >/dev/null && apt-get install -y -qq ffmpeg fonts-dejavu-core >/dev/null
  PYTHONDONTWRITEBYTECODE=1 python3 backend/fault_videos/fault_videos.py --data-dir /tmp/gen generate \
    --regions MUNTENIA --faults-per-region 0 --videos-per-region 1 --video-size-mb 4 --seed 7 >/dev/null
  # Smaller for the repository (about 65 KB); -map_metadata keeps the tags.
  ffmpeg -v error -y -i /tmp/gen/videos/MUNTENIA/*.mp4 -vf scale=640:-2 -c:v libx264 -crf 32 -preset slow \
    -map_metadata 0 -movflags +faststart /out/inspectie-otopeni.mp4
  chown "$(stat -c %u:%g /out)" /out/inspectie-otopeni.mp4
  ffprobe -v error -show_entries format_tags=comment,creation_time -of default=nw=1 /out/inspectie-otopeni.mp4'
echo "wrote demo/videos/inspectie-otopeni.mp4"
