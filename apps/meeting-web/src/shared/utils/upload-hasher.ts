// Single-pass upload hashing, off the main thread.
//
// The upload flow needs (a) the whole-file SHA-256 for the upload session
// and (b) one SHA-256 per part for the signed part uploads. Hashing used to
// run twice over the file (whole file, then every part again) on the main
// thread — a multi-hundred-MiB recording froze the UI for the entire
// "preparing" phase. This module reads the file once, feeding both the
// file-level and the per-part hashers from the same chunks, and runs the
// work in a Web Worker when available (falling back inline for jsdom /
// older environments) with byte-level progress for a real progress bar.

import { readBlobAsUint8Array, Sha256, toHex } from "./sha256-stream";

export interface UploadHashPart {
  partNumber: number;
  sizeBytes: number;
  partSha256: string;
}

export interface UploadHashResult {
  fileSha256: string;
  parts: UploadHashPart[];
}

export type UploadHashProgress = (bytesHashed: number, totalBytes: number) => void;

const READ_CHUNK_BYTES = 4 * 1024 * 1024;

/**
 * One pass over the Blob: every chunk updates the file hasher and the
 * current part hasher. Memory stays bounded at one read chunk.
 */
export async function hashFileForUploadInline(
  file: Blob,
  partSizeBytes: number,
  onProgress?: UploadHashProgress,
  readChunkBytes = READ_CHUNK_BYTES,
): Promise<UploadHashResult> {
  if (partSizeBytes <= 0) {
    throw new Error("partSizeBytes must be positive");
  }
  const fileHasher = new Sha256();
  const parts: UploadHashPart[] = [];
  const partCount = Math.max(1, Math.ceil(file.size / partSizeBytes));
  let bytesHashed = 0;
  for (let index = 0; index < partCount; index += 1) {
    const start = index * partSizeBytes;
    const end = Math.min(file.size, start + partSizeBytes);
    const partHasher = new Sha256();
    for (let offset = start; offset < end; offset += readChunkBytes) {
      const chunkEnd = Math.min(end, offset + readChunkBytes);
      const bytes = await readBlobAsUint8Array(file.slice(offset, chunkEnd));
      partHasher.update(bytes);
      fileHasher.update(bytes);
      bytesHashed += bytes.length;
      onProgress?.(bytesHashed, file.size);
    }
    parts.push({
      partNumber: index + 1,
      sizeBytes: end - start,
      partSha256: toHex(partHasher.digest()),
    });
  }
  return { fileSha256: toHex(fileHasher.digest()), parts };
}

/**
 * Hash in a dedicated Worker so the UI thread never blocks; transparently
 * falls back to the inline implementation when Workers are unavailable
 * (jsdom tests) or the worker fails to boot.
 */
export function hashFileForUpload(
  file: Blob,
  partSizeBytes: number,
  onProgress?: UploadHashProgress,
): Promise<UploadHashResult> {
  if (typeof Worker === "undefined") {
    return hashFileForUploadInline(file, partSizeBytes, onProgress);
  }
  return new Promise<UploadHashResult>((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new Worker(new URL("./upload-hash.worker.ts", import.meta.url), { type: "module" });
    } catch {
      void hashFileForUploadInline(file, partSizeBytes, onProgress).then(resolve, reject);
      return;
    }
    let settled = false;
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      worker.terminate();
      fn();
    };
    worker.onmessage = (event: MessageEvent<UploadHashWorkerMessage>) => {
      const message = event.data;
      if (message.type === "progress") {
        onProgress?.(message.bytesHashed, message.totalBytes);
      } else if (message.type === "done") {
        finish(() => resolve(message.result));
      } else if (message.type === "error") {
        finish(() => reject(new Error(message.message)));
      }
    };
    // A worker that fails to boot (CSP, bundling) must not lose the upload —
    // rerun inline instead.
    worker.onerror = () => {
      finish(() => {
        void hashFileForUploadInline(file, partSizeBytes, onProgress).then(resolve, reject);
      });
    };
    worker.postMessage({ file, partSizeBytes } satisfies UploadHashWorkerRequest);
  });
}

export interface UploadHashWorkerRequest {
  file: Blob;
  partSizeBytes: number;
}

export type UploadHashWorkerMessage =
  | { type: "progress"; bytesHashed: number; totalBytes: number }
  | { type: "done"; result: UploadHashResult }
  | { type: "error"; message: string };
