import { ApiError } from "@/shared/api/client";
import type { FileUploadCompleteResponseDTO, FileUploadPartDTO, FileUploadSessionDTO } from "@/shared/api/types";

const DEFAULT_PART_SIZE_BYTES = 5 * 1024 * 1024;
const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_RETRY_DELAY_MS = 250;

export class MultipartUploadError extends Error {
  public readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "MultipartUploadError";
    this.code = code;
  }
}

type InitFn = (req: {
  fileName: string;
  contentType: string;
  fileSizeBytes: number;
  fileSha256: string;
  partSizeBytes: number;
}) => Promise<FileUploadSessionDTO>;

type CreatePartFn = (
  uploadId: string,
  req: { partNumber: number; sizeBytes: number; partSha256: string },
) => Promise<FileUploadPartDTO>;

type CompleteFn<TResult> = (
  uploadId: string,
  req: { fileSha256: string; parts: { partNumber: number; partSha256: string; etag: string }[] },
) => Promise<TResult>;

type AbortFn = (uploadId: string) => Promise<unknown> | unknown;

export interface MultipartUploaderOptions<TResult = FileUploadCompleteResponseDTO> {
  file: File;
  partSizeBytes?: number;
  init: InitFn;
  createPart: CreatePartFn;
  complete: CompleteFn<TResult>;
  abort: AbortFn;
  onProgress?: (fraction: number) => void;
  maxRetries?: number;
  retryDelayMs?: number;
}

export class MultipartUploader<TResult = FileUploadCompleteResponseDTO> {
  private aborted = false;
  private uploadId: string | null = null;
  private readonly abortController = new AbortController();

  constructor(private readonly opts: MultipartUploaderOptions<TResult>) {}

  abort(): void {
    this.aborted = true;
    this.abortController.abort();
    if (this.uploadId) void this.abortSession(this.uploadId);
  }

  async upload(): Promise<TResult> {
    const partSizeBytes = this.opts.partSizeBytes ?? DEFAULT_PART_SIZE_BYTES;
    const fileSha256 = await sha256(await readBlobAsArrayBuffer(this.opts.file));
    let shouldAbortSession = false;

    try {
      this.throwIfAborted();
      const session = await this.opts.init({
        fileName: this.opts.file.name,
        contentType: this.opts.file.type || "application/octet-stream",
        fileSizeBytes: this.opts.file.size,
        fileSha256,
        partSizeBytes: partSizeBytes,
      });
      this.uploadId = session.uploadId;
      shouldAbortSession = true;
      if (this.aborted) {
        await this.abortSession(session.uploadId);
        throw new MultipartUploadError("UPLOAD_ABORTED", "upload aborted");
      }

      const effectivePartSizeBytes = session.partSizeBytes ?? partSizeBytes;
      const completed: { partNumber: number; partSha256: string; etag: string }[] = [];
      const totalParts = Math.max(1, Math.ceil(this.opts.file.size / effectivePartSizeBytes));
      let uploadedBytes = 0;

      for (let index = 0; index < totalParts; index += 1) {
        this.throwIfAborted();
        const partNumber = index + 1;
        const start = index * effectivePartSizeBytes;
        const end = Math.min(start + effectivePartSizeBytes, this.opts.file.size);
        const blob = this.opts.file.slice(start, end);
        const partSha256 = await sha256(await readBlobAsArrayBuffer(blob));
        const part =
          session.parts.find((candidate) => candidate.partNumber === partNumber) ??
          await this.opts.createPart(session.uploadId, {
            partNumber,
            sizeBytes: blob.size,
            partSha256,
          });
        const uploadUrl = part.uploadUrl ?? part.presignedUrl;
        if (!uploadUrl) throw new MultipartUploadError("UPLOAD_URL_MISSING", `part ${partNumber} has no upload URL`);

        const etag = await this.putWithRetry(uploadUrl, blob, part.headers ?? {});
        completed.push({ partNumber, partSha256, etag });
        uploadedBytes += blob.size;
        this.opts.onProgress?.(this.opts.file.size === 0 ? 1 : uploadedBytes / this.opts.file.size);
      }

      this.throwIfAborted();
      const result = await this.opts.complete(session.uploadId, { fileSha256, parts: completed });
      shouldAbortSession = false;
      this.opts.onProgress?.(1);
      return result;
    } catch (error) {
      if (this.uploadId && shouldAbortSession && !this.aborted) {
        await this.abortSession(this.uploadId);
      }
      throw normalizeUploadError(error);
    }
  }

  private async putWithRetry(url: string, blob: Blob, headers: Record<string, string>): Promise<string> {
    const maxRetries = this.opts.maxRetries ?? DEFAULT_MAX_RETRIES;
    for (let attempt = 1; attempt <= maxRetries; attempt += 1) {
      this.throwIfAborted();
      let response: Response;
      try {
        response = await fetch(url, {
          method: "PUT",
          body: blob,
          headers,
          signal: this.abortController.signal,
        });
      } catch (error) {
        if (this.aborted || this.abortController.signal.aborted) {
          throw new MultipartUploadError("UPLOAD_ABORTED", "upload aborted");
        }
        if (attempt === maxRetries) throw error;
        await this.delay(attempt);
        continue;
      }
      this.throwIfAborted();
      if (response.ok) return normalizeEtag(response.headers.get("etag"));
      if (attempt === maxRetries) {
        throw new MultipartUploadError("PART_UPLOAD_FAILED", `part upload failed after ${maxRetries} attempts (status ${response.status})`);
      }
      await this.delay(attempt);
    }
    throw new MultipartUploadError("PART_UPLOAD_FAILED", "part upload failed");
  }

  private delay(attempt: number) {
    return new Promise((resolve) =>
      setTimeout(resolve, (this.opts.retryDelayMs ?? DEFAULT_RETRY_DELAY_MS) * 2 ** (attempt - 1)),
    );
  }

  private throwIfAborted() {
    if (this.aborted || this.abortController.signal.aborted) {
      throw new MultipartUploadError("UPLOAD_ABORTED", "upload aborted");
    }
  }

  private async abortSession(uploadId: string) {
    await Promise.resolve(this.opts.abort(uploadId)).catch(() => undefined);
  }
}

function normalizeEtag(etag: string | null): string {
  return (etag ?? "").replace(/^"|"$/g, "");
}

function normalizeUploadError(error: unknown): unknown {
  if (error instanceof MultipartUploadError) return error;
  if (error instanceof ApiError && error.error.code === "FILE_MIME_NOT_ALLOWED") {
    return new MultipartUploadError(error.error.code, error.error.message);
  }
  return error;
}

async function sha256(buf: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function readBlobAsArrayBuffer(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === "function") return blob.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("failed to read blob"));
    reader.onload = () => {
      if (reader.result instanceof ArrayBuffer) {
        resolve(reader.result);
      } else {
        reject(new Error("unexpected blob read result"));
      }
    };
    reader.readAsArrayBuffer(blob);
  });
}
