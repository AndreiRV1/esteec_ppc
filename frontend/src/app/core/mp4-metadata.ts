/** What an MP4 container says about the flight; any field may be missing. */
export interface VideoMetadata {
  region?: string;
  county?: string;
  lineCode?: string;
  droneId?: string;
  /** ISO-8601 UTC. */
  flightStartedAt?: string;
  durationSeconds?: number;
}

const MAX_BOX_BYTES = 16 * 1024 * 1024; // moov of a 1 GB clip is well under this
const MP4_EPOCH_OFFSET = 2_082_844_800; // seconds from 1904-01-01 (MP4) to 1970-01-01 (Unix)

/**
 * Reads container metadata without loading the video: walks the top-level boxes with Blob slices and
 * parses only `moov` (mvhd: creation time and duration; udta: the comment tag) and small `free`/`udta`
 * boxes. FileGrid's drones (and the data generator) write "region=…;county=…;line_code=…;drone_id=…;
 * flight_started_at=…" into the comment.
 */
export async function readMp4Metadata(file: Blob): Promise<VideoMetadata> {
  const out: VideoMetadata = {};
  let comment = '';
  let offset = 0;
  for (let boxes = 0; boxes < 64 && offset + 8 <= file.size; boxes++) {
    const head = new DataView(await file.slice(offset, offset + 16).arrayBuffer());
    const type = String.fromCharCode(...new Uint8Array(head.buffer, 4, 4));
    let size = head.getUint32(0);
    let header = 8;
    if (size === 1 && head.byteLength >= 16) {
      size = Number(head.getBigUint64(8));
      header = 16;
    } else if (size === 0) {
      size = file.size - offset; // box runs to the end of the file
    }
    if (size < header) break; // not an MP4 (or damaged): stop
    if (['moov', 'free', 'udta'].includes(type) && size <= MAX_BOX_BYTES) {
      const body = new Uint8Array(await file.slice(offset + header, offset + size).arrayBuffer());
      if (type === 'moov') Object.assign(out, movieHeader(body));
      comment ||= /region=[^\x00-\x1f�]*/.exec(new TextDecoder().decode(body))?.[0] ?? '';
    }
    offset += size;
  }
  const tags = Object.fromEntries(comment.split(';').map((pair) => pair.split('=', 2).map((s) => s.trim())));
  if (tags['region']) out.region = tags['region'];
  if (tags['county']) out.county = tags['county'];
  if (tags['line_code']) out.lineCode = tags['line_code'];
  if (tags['drone_id']) out.droneId = tags['drone_id'];
  const flight = tags['flight_started_at'] ? new Date(tags['flight_started_at']) : null;
  if (flight && !Number.isNaN(flight.getTime())) out.flightStartedAt = flight.toISOString();
  return out;
}

/** Creation time and duration from the `mvhd` box inside `moov`. */
function movieHeader(moov: Uint8Array): VideoMetadata {
  const at = indexOf(moov, 'mvhd');
  if (at < 0 || at + 32 > moov.length) return {};
  const view = new DataView(moov.buffer, moov.byteOffset + at + 4);
  const v1 = view.getUint8(0) === 1;
  const created = v1 ? Number(view.getBigUint64(4)) : view.getUint32(4);
  const timescale = view.getUint32(v1 ? 20 : 12);
  const duration = v1 ? Number(view.getBigUint64(24)) : view.getUint32(16);
  const out: VideoMetadata = {};
  // 0 means "not set" (ffmpeg writes 0 unless creation_time is given).
  if (created > MP4_EPOCH_OFFSET) out.flightStartedAt = new Date((created - MP4_EPOCH_OFFSET) * 1000).toISOString();
  if (timescale > 0 && duration > 0) out.durationSeconds = Math.round(duration / timescale);
  return out;
}

function indexOf(bytes: Uint8Array, ascii: string): number {
  const codes = [...ascii].map((c) => c.charCodeAt(0));
  outer: for (let i = 0; i + codes.length <= bytes.length; i++) {
    for (let j = 0; j < codes.length; j++) if (bytes[i + j] !== codes[j]) continue outer;
    return i;
  }
  return -1;
}
