import { describe, expect, it } from "vitest";
import { hashFileForUpload, hashFileForUploadInline } from "../upload-hasher";
import { sha256Hex } from "../sha256-stream";

function makeBlob(size: number): Blob {
  const bytes = new Uint8Array(size);
  for (let i = 0; i < size; i += 1) bytes[i] = i % 251;
  return new Blob([bytes]);
}

describe("hashFileForUploadInline", () => {
  it("matches the known SHA-256 of 'abc' for a single part", async () => {
    const result = await hashFileForUploadInline(new Blob(["abc"]), 1024);
    expect(result.fileSha256).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
    expect(result.parts).toHaveLength(1);
    // A single part covers the whole file, so its hash equals the file hash.
    expect(result.parts[0]!.partSha256).toBe(result.fileSha256);
  });

  it("produces the same file hash as the two-pass sha256Hex and correct per-part hashes", async () => {
    const blob = makeBlob(10_000);
    const partSize = 3_000;
    const result = await hashFileForUploadInline(blob, partSize, undefined, 1_024);

    expect(result.fileSha256).toBe(await sha256Hex(blob));
    expect(result.parts).toHaveLength(4);
    expect(result.parts.map((p) => p.sizeBytes)).toEqual([3000, 3000, 3000, 1000]);
    for (const part of result.parts) {
      const start = (part.partNumber - 1) * partSize;
      const expected = await sha256Hex(blob.slice(start, start + part.sizeBytes));
      expect(part.partSha256).toBe(expected);
    }
  });

  it("reports monotonic byte progress up to the file size", async () => {
    const blob = makeBlob(5_000);
    const seen: number[] = [];
    await hashFileForUploadInline(blob, 2_000, (bytesHashed, totalBytes) => {
      expect(totalBytes).toBe(5_000);
      seen.push(bytesHashed);
    }, 1_000);
    expect(seen.at(-1)).toBe(5_000);
    expect([...seen].sort((a, b) => a - b)).toEqual(seen);
  });
});

describe("hashFileForUpload", () => {
  it("falls back to inline hashing when Worker is unavailable (jsdom)", async () => {
    const blob = makeBlob(4_096);
    const viaFacade = await hashFileForUpload(blob, 1_024);
    const inline = await hashFileForUploadInline(blob, 1_024);
    expect(viaFacade).toEqual(inline);
  });
});
