import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';

const CHUNK = 4 * 1024 * 1024;

/**
 * SHA-256 of a file of any size, read 4 MB at a time, so memory use stays flat.
 * (WebCrypto's digest() needs the whole file in memory, hence the incremental hasher.)
 */
// ponytail: runs on the main thread, yielding between chunks. Move to a Web Worker
// if hashing multi-GB files makes the page feel slow.
export async function sha256OfBlob(blob: Blob, onProgress?: (fraction: number) => void): Promise<string> {
  const hasher = sha256.create();
  for (let offset = 0; offset < blob.size; offset += CHUNK) {
    hasher.update(new Uint8Array(await blob.slice(offset, offset + CHUNK).arrayBuffer()));
    onProgress?.(Math.min(1, (offset + CHUNK) / blob.size));
  }
  return bytesToHex(hasher.digest());
}
