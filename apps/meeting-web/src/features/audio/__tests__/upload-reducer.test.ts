import { describe, expect, it } from "vitest";
import { initialUploadState, uploadReducer, type UploadPartState } from "../upload-reducer";

const parts: UploadPartState[] = [
  { partNumber: 1, sizeBytes: 10, partSha256: "a".repeat(64), attempts: 0, status: "pending" },
  { partNumber: 2, sizeBytes: 10, partSha256: "b".repeat(64), attempts: 0, status: "pending" },
];

describe("uploadReducer", () => {
  it("tracks part completion progress", () => {
    const withSession = uploadReducer(initialUploadState, {
      type: "session",
      session: {
        uploadId: "upl_01",
        meetingId: "mtg_01",
        uploadStatus: "UPLOADING",
        expiresAt: "2026-05-15T00:00:00Z",
        partSizeBytes: 8,
        maxPartCount: 10000,
        contentType: "audio/wav",
        fileName: "a.wav",
        fileSizeBytes: 20,
        fileSha256: "c".repeat(64),
        parts: [],
      },
      parts,
    });

    const completed = uploadReducer(withSession, { type: "part-complete", partNumber: 1, etag: "etag_01" });

    expect(completed.progress).toBe(50);
    expect(completed.parts[0]?.status).toBe("completed");
    expect(completed.parts[0]?.etag).toBe("etag_01");
  });

  it("marks hash mismatch as failed", () => {
    const withSession = uploadReducer(initialUploadState, {
      type: "session",
      session: {
        uploadId: "upl_01",
        meetingId: "mtg_01",
        uploadStatus: "UPLOADING",
        expiresAt: "2026-05-15T00:00:00Z",
        partSizeBytes: 8,
        maxPartCount: 10000,
        contentType: "audio/wav",
        fileName: "a.wav",
        fileSizeBytes: 20,
        fileSha256: "c".repeat(64),
        parts: [],
      },
      parts,
    });

    const failed = uploadReducer(withSession, {
      type: "part-failed",
      partNumber: 1,
      errorCode: "UPLOAD_PART_HASH_MISMATCH",
    });

    expect(failed.status).toBe("failed");
    expect(failed.errorCode).toBe("UPLOAD_PART_HASH_MISMATCH");
  });
});
