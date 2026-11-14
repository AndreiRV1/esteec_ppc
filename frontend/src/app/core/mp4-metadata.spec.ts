import { describe, expect, it } from 'vitest';
import { readMp4Metadata } from './mp4-metadata';

const ascii = (s: string) => new TextEncoder().encode(s);

function box(type: string, ...parts: Uint8Array[]): Uint8Array {
  const size = 8 + parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(size);
  new DataView(out.buffer).setUint32(0, size);
  out.set(ascii(type), 4);
  let at = 8;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** mvhd version 0: created 2026-11-14T08:00:00Z, timescale 1000, duration 12.4 s. */
function mvhd(): Uint8Array {
  const body = new Uint8Array(100);
  const view = new DataView(body.buffer);
  view.setUint32(4, Date.UTC(2026, 10, 14, 8, 0, 0) / 1000 + 2_082_844_800);
  view.setUint32(12, 1000);
  view.setUint32(16, 12_400);
  return box('mvhd', body);
}

const COMMENT = 'region=BANAT;category=DRONE_VIDEO;county=Timiș;line_code=LEA-110kV-BAN-TM-01;drone_id=DRN-BAN-07';

describe('readMp4Metadata', () => {
  it('reads mvhd and the comment tag of an ffmpeg-written file', async () => {
    const file = new Blob([
      box('ftyp', ascii('isom')),
      box('moov', mvhd(), box('udta', box('data', ascii(COMMENT)))),
      box('mdat', new Uint8Array(1000)),
    ] as BlobPart[]);
    expect(await readMp4Metadata(file)).toEqual({
      region: 'BANAT', county: 'Timiș', lineCode: 'LEA-110kV-BAN-TM-01', droneId: 'DRN-BAN-07',
      flightStartedAt: '2026-11-14T08:00:00.000Z', durationSeconds: 12,
    });
  });

  it('reads the free-box comment of a synthetic file with a 64-bit mdat', async () => {
    const mdat = new Uint8Array(16 + 64);
    const view = new DataView(mdat.buffer);
    view.setUint32(0, 1);
    mdat.set(ascii('mdat'), 4);
    view.setBigUint64(8, BigInt(mdat.length));
    const file = new Blob([box('ftyp', ascii('isom')), box('free', ascii(COMMENT + ';flight_started_at=2026-11-02T09:30:00Z')), mdat] as BlobPart[]);
    const meta = await readMp4Metadata(file);
    expect(meta.region).toBe('BANAT');
    expect(meta.flightStartedAt).toBe('2026-11-02T09:30:00.000Z');
    expect(meta.durationSeconds).toBeUndefined();
  });

  it('returns nothing for a file that is not an MP4', async () => {
    expect(await readMp4Metadata(new Blob([ascii('just some text, not boxes')]))).toEqual({});
  });
});
