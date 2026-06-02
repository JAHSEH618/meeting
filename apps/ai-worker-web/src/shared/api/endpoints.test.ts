import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  abortAudioUpload,
  abortFileUpload,
  completeAudioUpload,
  completeFileUpload,
  confirmSpeaker,
  createAudioUploadPart,
  createDocument,
  createFileUploadPart,
  createPerson,
  getProcessingTask,
  initAudioUpload,
  initFileUpload,
  listSpeakerProfiles,
  processingTaskEventsUrl,
  rejectSpeaker,
  revokeSpeakerProfile,
  searchPersons,
} from "./endpoints";
import { authStore } from "@/shared/auth/store";

describe("admin endpoint helpers", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    authStore.clear();
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  it("creates persons and normalizes legacy id fields", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ id: "p1", displayName: "李四", email: null }));

    const person = await createPerson({ displayName: "李四" });

    expect(person.personId).toBe("p1");
    expect(fetchMock).toHaveBeenCalledWith(
      "/admin/persons",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("searches persons with query params", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([{ personId: "p1", displayName: "李四", email: null }]));

    const persons = await searchPersons("李四");

    expect(persons[0]?.personId).toBe("p1");
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/persons?q=%E6%9D%8E%E5%9B%9B");
  });

  it("exposes generic file upload lifecycle helpers", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ uploadId: "u1", parts: [] }))
      .mockResolvedValueOnce(jsonResponse({ partNumber: 1, uploadUrl: "https://upload/1", expiresAt: "", headers: {} }))
      .mockResolvedValueOnce(jsonResponse({ fileId: "f1", sha256: "a", sizeBytes: 1, contentType: "application/pdf" }))
      .mockResolvedValueOnce(jsonResponse(null));

    await initFileUpload({ fileName: "ref.pdf", contentType: "application/pdf", fileSizeBytes: 1, fileSha256: "a" });
    await createFileUploadPart("u1", { partNumber: 1, sizeBytes: 1, partSha256: "b" });
    await completeFileUpload("u1", { fileSha256: "a", parts: [{ partNumber: 1, partSha256: "b", etag: "e" }] });
    await abortFileUpload("u1");

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/admin/files/uploads",
      "/admin/files/uploads/u1/parts",
      "/admin/files/uploads/u1/complete",
      "/admin/files/uploads/u1/abort",
    ]);
  });

  it("exposes meeting audio upload lifecycle helpers", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ uploadId: "u1", parts: [] }))
      .mockResolvedValueOnce(jsonResponse({ partNumber: 1, uploadUrl: "https://upload/1", expiresAt: "", headers: {} }))
      .mockResolvedValueOnce(jsonResponse({ uploadId: "u1", uploadStatus: "COMPLETED", fileId: "f1" }))
      .mockResolvedValueOnce(jsonResponse(null));

    await initAudioUpload("m1", { fileName: "a.mp3", contentType: "audio/mpeg", fileSizeBytes: 1, fileSha256: "a" });
    await createAudioUploadPart("m1", "u1", { partNumber: 1, sizeBytes: 1, partSha256: "b" });
    await completeAudioUpload("m1", "u1", { fileSha256: "a", parts: [{ partNumber: 1, partSha256: "b", etag: "e" }] });
    await abortAudioUpload("m1", "u1");

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/admin/meetings/m1/files/audio/uploads",
      "/admin/meetings/m1/files/audio/uploads/u1/parts",
      "/admin/meetings/m1/files/audio/uploads/u1/complete",
      "/admin/meetings/m1/files/audio/uploads/u1/abort",
    ]);
  });

  it("creates documents from uploaded generic files", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ documentId: "d1", title: "ref.pdf" }));

    const document = await createDocument({
      title: "ref.pdf",
      fileId: "f1",
      documentType: "PDF",
      securityLevel: "INTERNAL",
      contentHash: "a",
    });

    expect(document.documentId).toBe("d1");
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/documents");
  });

  it("confirms speaker candidates with Java transcript concurrency fields", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      speakerLabel: "SPEAKER_01",
      confirmationStatus: "MANUALLY_CONFIRMED",
      candidates: [],
    }));

    await confirmSpeaker("m1", "SPEAKER_01", {
      personId: "p1",
      speakerProfileId: "spk1",
      expectedTranscriptVersion: 3,
    });

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/meetings/m1/speakers/SPEAKER_01:confirm");
    const [, init] = fetchMock.mock.calls[0]!;
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({
      personId: "p1",
      speakerProfileId: "spk1",
      expectedTranscriptVersion: 3,
    });
  });

  it("rejects incorrect speaker candidates through Java", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(null));

    await rejectSpeaker("m1", "SPEAKER_01");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/meetings/m1/speakers/SPEAKER_01:reject");
    const [, init] = fetchMock.mock.calls[0]!;
    expect((init as RequestInit).method).toBe("POST");
  });

  it("exposes task detail and SSE helpers", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      taskId: "task 1",
      meetingId: "m1",
      status: "RUNNING",
      phase: "WORKER_DAG_RUNNING",
      attemptNo: 1,
      currentStep: "ASR",
      lastErrorCode: null,
      retryable: true,
      steps: [],
    }));

    await getProcessingTask("task 1");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/processing-tasks/task%201");
    expect(processingTaskEventsUrl("task 1")).toBe("/api/processing-tasks/task%201/events");
  });

  it("uses speaker-profile semantics for voiceprint administration", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse([
        {
          speakerProfileId: "sp1",
          personId: "p1",
          displayName: "李四",
          consentStatus: "ACTIVE",
          enrollmentCount: 2,
          lastEnrolledAt: "2026-06-02T00:00:00Z",
        },
      ]))
      .mockResolvedValueOnce(jsonResponse(null));

    const profiles = await listSpeakerProfiles("p1");
    await revokeSpeakerProfile("sp1", "operator_request");

    expect(profiles[0]).toMatchObject({
      speakerProfileId: "sp1",
      personId: "p1",
      displayName: "李四",
      status: "ACTIVE",
      enrollmentCount: 2,
    });
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/voiceprints?personId=p1");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/admin/voiceprints/sp1:revoke");
    const [, init] = fetchMock.mock.calls[1]!;
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ reason: "operator_request" });
  });
});

function jsonResponse(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ success: status < 400, data, error: null, requestId: "r", traceId: "t" }),
    { status, headers: { "Content-Type": "application/json" } },
  );
}
