import { HttpErrorResponse } from '@angular/common/http';
import { UploadPausedError, UploadProgress, UploadTransport, partCount, partRange, uploadParts } from './chunked-upload';

const PART = 4;
const file = new Blob(['0123456789']); // 10 bytes -> parts of 4, 4 and 2 bytes
const httpError = (status: number) => new HttpErrorResponse({ status });

/** A fake server that remembers which parts it has, like the real upload session. */
function server(received: number[] = []) {
  const parts = new Map<number, string>();
  const calls: number[] = [];
  const transport: UploadTransport = {
    status: async () => ({ status: 'IN_PROGRESS', bytesReceived: 0, receivedParts: [...received, ...parts.keys()] }),
    putPart: async (_id, partNumber, part) => {
      calls.push(partNumber);
      parts.set(partNumber, await part.text());
    },
  };
  return { transport, parts, calls };
}

const noWait = { partSize: PART, sleep: async () => {} };

describe('chunked upload', () => {
  it('splits a file into 1-based parts with a short last part', () => {
    expect(partCount(10, 4)).toBe(3);
    expect(partCount(8, 4)).toBe(2);
    expect(partCount(0, 4)).toBe(1);
    expect(partRange(1, 10, 4)).toEqual([0, 4]);
    expect(partRange(3, 10, 4)).toEqual([8, 10]);
  });

  it('uploads every part, in order, with the right bytes', async () => {
    const { transport, parts, calls } = server();
    await uploadParts(file, 'u1', transport, noWait);
    expect(calls).toEqual([1, 2, 3]);
    expect([...parts.values()]).toEqual(['0123', '4567', '89']);
  });

  it('resumes: sends only the parts the server does not have, and counts the rest as done', async () => {
    const { transport, calls } = server([1, 3]);
    const progress: UploadProgress[] = [];
    await uploadParts(file, 'u1', transport, { ...noWait, onProgress: (p) => progress.push(p) });
    expect(calls).toEqual([2]);
    expect(progress[0]).toEqual({ bytesDone: 6, bytesTotal: 10 }); // parts 1 (4 bytes) + 3 (2 bytes)
    expect(progress.at(-1)).toEqual({ bytesDone: 10, bytesTotal: 10 });
  });

  it('retries a failing part with growing delays, then carries on', async () => {
    const { transport, calls } = server();
    const putPart = transport.putPart;
    let failures = 2;
    transport.putPart = async (id, partNumber, part) => {
      if (partNumber === 2 && failures-- > 0) throw httpError(503);
      return putPart(id, partNumber, part);
    };
    const delays: number[] = [];
    await uploadParts(file, 'u1', transport, { partSize: PART, baseDelayMs: 100, sleep: async (ms) => void delays.push(ms) });
    expect(calls).toEqual([1, 2, 3]);
    expect(delays).toHaveLength(2);
    expect(delays[0]).toBeGreaterThanOrEqual(75); // 100 ms ± 25 % jitter
    expect(delays[0]).toBeLessThanOrEqual(125);
    expect(delays[1]).toBeGreaterThanOrEqual(150); // 200 ms ± 25 %
  });

  it('gives up after the maximum number of attempts', async () => {
    const { transport } = server();
    let attempts = 0;
    transport.putPart = async () => {
      attempts++;
      throw httpError(0); // network unreachable
    };
    await expect(uploadParts(file, 'u1', transport, { ...noWait, maxAttempts: 3 })).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(attempts).toBe(3);
  });

  it('does not retry errors that retrying cannot fix', async () => {
    const { transport } = server();
    let attempts = 0;
    transport.putPart = async () => {
      attempts++;
      throw httpError(404); // the upload session is gone
    };
    await expect(uploadParts(file, 'u1', transport, noWait)).rejects.toMatchObject({ status: 404 });
    expect(attempts).toBe(1);
  });

  it('pauses between parts and continues later from where it stopped', async () => {
    const { transport, calls, parts } = server();
    const controller = new AbortController();
    const putPart = transport.putPart;
    transport.putPart = async (id, partNumber, part) => {
      await putPart(id, partNumber, part);
      if (partNumber === 1) controller.abort(); // user presses "Pauză" during part 1
    };
    await expect(uploadParts(file, 'u1', transport, { ...noWait, signal: controller.signal }))
      .rejects.toBeInstanceOf(UploadPausedError);
    expect(calls).toEqual([1]);

    transport.putPart = putPart;
    await uploadParts(file, 'u1', transport, noWait); // e.g. after a page reload
    expect(calls).toEqual([1, 2, 3]);
    expect([...parts.entries()].sort().map(([, text]) => text).join('')).toBe('0123456789');
  });
});
