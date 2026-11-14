import { UploadStatus } from './models';
import { toProblem } from './api';

/** The two server calls the uploader needs; ApiService provides them, tests fake them. */
export interface UploadTransport {
  status(uploadId: string): Promise<UploadStatus>;
  putPart(uploadId: string, partNumber: number, part: Blob): Promise<void>;
}

export interface UploadProgress {
  bytesDone: number;
  bytesTotal: number;
  /** Set while waiting to retry a failed part. */
  retrying?: { partNumber: number; attempt: number; delayMs: number };
}

export interface UploadOptions {
  partSize: number;
  maxAttempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  onProgress?: (progress: UploadProgress) => void;
  /** Abort to pause: the upload can be resumed later with the same uploadId. */
  signal?: AbortSignal;
  /** Injectable for tests. */
  sleep?: (ms: number) => Promise<void>;
}

export class UploadPausedError extends Error {
  constructor() {
    super('Upload paused');
  }
}

export function partCount(size: number, partSize: number): number {
  return Math.max(1, Math.ceil(size / partSize));
}

/** Byte range [start, end) of a 1-based part. */
export function partRange(partNumber: number, size: number, partSize: number): [number, number] {
  const start = (partNumber - 1) * partSize;
  return [start, Math.min(size, start + partSize)];
}

/** Network errors, timeouts, throttling and server errors are worth retrying; other 4xx are not. */
export function isRetryable(error: unknown): boolean {
  const status = toProblem(error).status;
  return status === 0 || status === 408 || status === 429 || status >= 500;
}

/**
 * Uploads every part the server does not have yet. It always asks the server
 * first, so the same call both starts a fresh upload and resumes one after a
 * pause, a dropped connection or a page reload. Each part is retried with
 * exponential backoff; File.slice reads one part at a time, never the whole file.
 */
// ponytail: parts go one at a time. Send 3-4 in parallel if throughput on good links matters.
export async function uploadParts(
  file: Blob,
  uploadId: string,
  transport: UploadTransport,
  options: UploadOptions,
): Promise<void> {
  const { partSize, maxAttempts = 6, baseDelayMs = 500, maxDelayMs = 20_000, onProgress, signal } = options;
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));

  const attempt = async <T>(partNumber: number, action: () => Promise<T>): Promise<T> => {
    for (let tries = 1; ; tries++) {
      if (signal?.aborted) throw new UploadPausedError();
      try {
        return await action();
      } catch (error) {
        if (tries >= maxAttempts || !isRetryable(error)) throw error;
        const delayMs = Math.min(maxDelayMs, baseDelayMs * 2 ** (tries - 1)) * (0.75 + Math.random() * 0.5);
        onProgress?.({ bytesDone, bytesTotal: file.size, retrying: { partNumber, attempt: tries + 1, delayMs } });
        await sleep(delayMs);
      }
    }
  };

  let bytesDone = 0;
  const received = new Set((await attempt(0, () => transport.status(uploadId))).receivedParts);
  const total = partCount(file.size, partSize);
  const sizeOf = (partNumber: number) => {
    const [start, end] = partRange(partNumber, file.size, partSize);
    return end - start;
  };
  for (let part = 1; part <= total; part++) if (received.has(part)) bytesDone += sizeOf(part);
  onProgress?.({ bytesDone, bytesTotal: file.size });

  for (let part = 1; part <= total; part++) {
    if (received.has(part)) continue;
    const [start, end] = partRange(part, file.size, partSize);
    await attempt(part, () => transport.putPart(uploadId, part, file.slice(start, end)));
    bytesDone += end - start;
    onProgress?.({ bytesDone, bytesTotal: file.size });
  }
}
