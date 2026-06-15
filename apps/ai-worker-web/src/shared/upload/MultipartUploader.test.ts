import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/client";
import { MultipartUploader } from "./MultipartUploader";

const PART = 5 * 1024 * 1024;
const FOUR_ZERO_BYTES_SHA256 = "df3f619804a92fdb4057192dc43dd748ea778adc52bc498ce80524c014b81119";

function file(size: number, type = "application/pdf"): File {
  return new File([new Uint8Array(size)], "ref.pdf", { type });
}

describe("MultipartUploader", () => {
  beforeEach(() => {
    vi.useRealTimers();
    globalThis.fetch = vi.fn(async (url: RequestInfo | URL) => {
      if (String(url).startsWith("https://presign/")) {
        return new Response("", { status: 200, headers: { etag: '"etag-1"' } });
      }
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
  });

  it("uploads small files in one part using session-provided URLs", async () => {
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: { "x-test": "1" } }],
    }));
    const createPart = vi.fn();
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, createPart, complete, abort: vi.fn() });

    const result = await uploader.upload();

    expect(result.fileId).toBe("f1");
    expect(createPart).not.toHaveBeenCalled();
    expect(complete).toHaveBeenCalledWith("u1", expect.objectContaining({
      parts: [expect.objectContaining({ partNumber: 1, etag: "etag-1" })],
    }));
  });

  it("creates presigned URLs for sessions with empty parts", async () => {
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [] }));
    const createPart = vi.fn(async () => ({ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, createPart, complete, abort: vi.fn() });

    await uploader.upload();

    expect(createPart).toHaveBeenCalledWith("u1", expect.objectContaining({ partNumber: 1, sizeBytes: 4 }));
  });

  it("hashes blobs without relying on WebCrypto digest realm compatibility", async () => {
    const digestSpy = vi.spyOn(crypto.subtle, "digest").mockRejectedValue(
      new TypeError("strict WebCrypto BufferSource check"),
    );
    const init = vi.fn(async () => ({ uploadId: "u1", parts: [] }));
    const createPart = vi.fn(async () => ({ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, createPart, complete, abort: vi.fn() });

    try {
      await uploader.upload();
    } finally {
      digestSpy.mockRestore();
    }

    expect(digestSpy).not.toHaveBeenCalled();
    expect(init).toHaveBeenCalledWith(expect.objectContaining({
      fileSha256: FOUR_ZERO_BYTES_SHA256,
    }));
    expect(complete).toHaveBeenCalledWith("u1", expect.objectContaining({
      fileSha256: FOUR_ZERO_BYTES_SHA256,
      parts: [expect.objectContaining({ partNumber: 1, partSha256: FOUR_ZERO_BYTES_SHA256 })],
    }));
  });

  it("uses server-returned partSizeBytes when Java coerces uploads to single PUT", async () => {
    const init = vi.fn(async () => ({ uploadId: "u1", partSizeBytes: PART * 3, parts: [] }));
    const createPart = vi.fn(async () => ({ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: PART * 2 + 7, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({
      file: file(PART * 2 + 7),
      partSizeBytes: PART,
      init,
      createPart,
      complete,
      abort: vi.fn(),
    });

    await uploader.upload();

    expect(createPart).toHaveBeenCalledTimes(1);
    expect(createPart).toHaveBeenCalledWith("u1", expect.objectContaining({
      partNumber: 1,
      sizeBytes: PART * 2 + 7,
    }));
    expect(complete).toHaveBeenCalledWith("u1", expect.objectContaining({
      parts: [expect.objectContaining({ partNumber: 1 })],
    }));
  });

  it("emits progress fractions through completion", async () => {
    const events: number[] = [];
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({
      file: file(4),
      partSizeBytes: PART,
      init,
      createPart: vi.fn(),
      complete,
      abort: vi.fn(),
      onProgress: (progress) => events.push(progress),
    });

    await uploader.upload();

    expect(events.at(-1)).toBe(1);
  });

  it("retries a failing part three times before succeeding", async () => {
    let attempts = 0;
    globalThis.fetch = vi.fn(async () => {
      attempts += 1;
      if (attempts < 3) return new Response("", { status: 500 });
      return new Response("", { status: 200, headers: { etag: '"ok"' } });
    }) as unknown as typeof fetch;
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, createPart: vi.fn(), complete, abort: vi.fn(), retryDelayMs: 1 });

    await uploader.upload();

    expect(attempts).toBe(3);
  });

  it("aborts initialized uploads when a part fails permanently", async () => {
    globalThis.fetch = vi.fn(async () => new Response("", { status: 500 })) as unknown as typeof fetch;
    const abort = vi.fn();
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const uploader = new MultipartUploader({
      file: file(4),
      partSizeBytes: PART,
      init,
      createPart: vi.fn(),
      complete: vi.fn(),
      abort,
      retryDelayMs: 1,
    });

    await expect(uploader.upload()).rejects.toThrow(/part upload failed/i);
    expect(abort).toHaveBeenCalledWith("u1");
  });

  it("surfaces FILE_MIME_NOT_ALLOWED as a clean error without aborting", async () => {
    const abort = vi.fn();
    const uploader = new MultipartUploader({
      file: file(4, "application/x-msdownload"),
      init: vi.fn(async () => {
        throw new ApiError(415, { code: "FILE_MIME_NOT_ALLOWED", message: "not allowed", retryable: false }, "r", "t");
      }),
      createPart: vi.fn(),
      complete: vi.fn(),
      abort,
    });

    await expect(uploader.upload()).rejects.toMatchObject({ code: "FILE_MIME_NOT_ALLOWED" });
    expect(abort).not.toHaveBeenCalled();
  });

  it("abort cancels in-flight upload after session init", async () => {
    let resolveFetch: () => void = () => {};
    globalThis.fetch = vi.fn(async () => new Promise<Response>((resolve) => {
      resolveFetch = () => resolve(new Response("", { status: 200, headers: { etag: '"ok"' } }));
    })) as unknown as typeof fetch;
    const abort = vi.fn();
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const uploader = new MultipartUploader({
      file: file(4),
      partSizeBytes: PART,
      init,
      createPart: vi.fn(),
      complete: vi.fn(),
      abort,
    });

    const pending = uploader.upload();
    await vi.waitFor(() => expect(init).toHaveBeenCalled());
    uploader.abort();
    resolveFetch();

    await expect(pending).rejects.toThrow(/aborted/i);
    expect(abort).toHaveBeenCalledWith("u1");
  });
});
