import { http, HttpResponse } from "msw";
import type { ApiResponse, Meeting, AuthUser, ProcessingTask, AudioUploadSession, TranscriptData, MinutesData } from "../types";

const meetingList: Meeting[] = [
  {
    meetingId: "mtg_01",
    tenantId: "tenant_01",
    title: "产品周会",
    securityLevel: "INTERNAL",
    status: "CREATED",
    language: "zh",
    transcriptVersion: 0,
    minutesVersion: 0,
    createdAt: "2026-05-11T09:00:00Z",
  },
];

// Phase 6 export mock state — per-meeting list ordered most-recent first.
interface MockExportJob {
  exportId: string;
  meetingId: string;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "REVOKED";
  format: "MARKDOWN" | "DOCX" | "PDF";
  dataBoundaryMode: string;
  inputTranscriptVersion: number;
  inputMinutesVersion: number | null;
  snapshotManifestId: string;
  watermarkText: string | null;
  downloadUrl: string | null;
  downloadUrlExpiresAt: string | null;
  sha256: string | null;
  fileSizeBytes: number | null;
  revoked: boolean;
  stale: boolean;
  errorCode: string | null;
  expiresAt: string;
  createdAt: string;
  finishedAt: string | null;
}
const exportsByMeeting: Record<string, MockExportJob[]> = {};
let exportIdCounter = 0;

// Phase 7 legal hold mock state.
interface MockLegalHold {
  legalHoldId: string;
  scopeType: "MEETING" | "DOCUMENT" | "SPEAKER_PROFILE" | "PROJECT";
  scopeId: string;
  reason: string;
  status: "ACTIVE" | "RELEASED";
  requestedBy: string;
  approvedBy: string | null;
  createdAt: string;
  releasedAt: string | null;
  releasedBy: string | null;
  releaseReason: string | null;
}
const legalHolds: MockLegalHold[] = [];

// Phase 7.3 deletion job mock state.
interface MockDeletionJob {
  deletionJobId: string;
  scopeType: "MEETING" | "DOCUMENT" | "SPEAKER_PROFILE" | "USER" | "PROJECT" | "TENANT";
  scopeId: string;
  status: "REQUESTED" | "PENDING_APPROVAL" | "RUNNING" | "SUCCEEDED" | "PARTIAL_FAILED" | "FAILED" | "BLOCKED_BY_LEGAL_HOLD";
  requestedBy: string;
  approvedBy: string | null;
  legalHoldChecked: boolean;
  deletedRows: Record<string, unknown>;
  deletedFiles: Record<string, unknown>;
  kmsKeysDestroyed: Record<string, unknown>;
  certificateHash: string | null;
  errorCode: string | null;
  createdAt: string;
  finishedAt: string | null;
}
const deletionJobs: MockDeletionJob[] = [];

// Phase 7.4 break-glass mock state.
interface MockBreakGlassRequest {
  breakGlassRequestId: string;
  requesterId: string;
  scopeType: string;
  scopeId: string;
  reason: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | "REVOKED";
  validFrom: string | null;
  validUntil: string | null;
  approverId: string | null;
  approvedAt: string | null;
  rejectedAt: string | null;
  rejectReason: string | null;
  revokedAt: string | null;
  revokedBy: string | null;
  createdAt: string;
}
const breakGlassRequests: MockBreakGlassRequest[] = [];

// Phase 7.5 audit events mock state (read-only seed for UI tests).
interface MockAuditEvent {
  auditEventId: string;
  actorUserId: string | null;
  actorType: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string;
  reason: string | null;
  traceId: string | null;
  payload: Record<string, unknown>;
  createdAt: string;
}
const mockAuditEvents: MockAuditEvent[] = [
  {
    auditEventId: "audit_mock_01",
    actorUserId: "user_compliance",
    actorType: "USER",
    action: "LEGAL_HOLD_PLACE",
    resourceType: "LEGAL_HOLD",
    resourceId: "lh_mock_01",
    result: "SUCCESS",
    reason: null,
    traceId: "trace_mock_01",
    payload: { scopeType: "MEETING", scopeId: "mtg_mock_01" },
    createdAt: "2026-05-17T10:00:00Z",
  },
  {
    auditEventId: "audit_mock_02",
    actorUserId: "user_compliance",
    actorType: "USER",
    action: "DELETION_REQUEST",
    resourceType: "DELETION_JOB",
    resourceId: "dj_mock_01",
    result: "BLOCKED",
    reason: "blocked by legal hold",
    traceId: "trace_mock_02",
    payload: {},
    createdAt: "2026-05-17T11:30:00Z",
  },
];

const uploadSession: AudioUploadSession = {
  uploadId: "upl_01",
  meetingId: "mtg_01",
  uploadStatus: "UPLOADING",
  expiresAt: "2026-05-15T09:00:00Z",
  partSizeBytes: 8 * 1024 * 1024,
  maxPartCount: 10000,
  objectKey: "meeting-audio/tenant_01/mtg_01/upl_01/raw",
  bucket: "meeting-audio",
  contentType: "audio/wav",
  fileName: "standup.wav",
  fileSizeBytes: 128,
  fileSha256: "a".repeat(64),
  parts: [],
};

const transcript: TranscriptData = {
  meetingId: "mtg_01",
  transcriptVersion: 1,
  staleStatus: "CURRENT",
  segments: [
    {
      segmentId: "seg_01",
      speakerLabel: "SPEAKER_00",
      speakerDisplayName: null,
      startMs: 0,
      endMs: 1800,
      originalText: "今天先确认阶段二验收范围。",
      editedText: null,
      currentText: "今天先确认阶段二验收范围。",
      asrConfidence: 0.93,
      diarizationConfidence: 0.88,
      timestampPrecision: "SEGMENT",
    },
  ],
};

export const handlers = [
  http.post("/api/auth/login", () => {
    return HttpResponse.json<ApiResponse<{ accessToken: string; expiresAt: string; user: AuthUser }>>({
      success: true,
      data: {
        accessToken: "mock-access-token",
        expiresAt: new Date(Date.now() + 3600000).toISOString(),
        user: {
          userId: "user_01",
          tenantId: "tenant_01",
          displayName: "测试用户",
          roles: ["admin"],
          permissions: ["meeting:create", "meeting:read"],
        },
      },
      error: null,
      requestId: "req_01",
      traceId: "trace_01",
    });
  }),

  http.post("/api/auth/logout", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: null,
      error: null,
      requestId: "req_02",
      traceId: "trace_02",
    });
  }),

  http.get("/api/auth/me", () => {
    return HttpResponse.json<ApiResponse<AuthUser>>({
      success: true,
      data: {
        userId: "user_01",
        tenantId: "tenant_01",
        displayName: "测试用户",
        roles: ["admin"],
        permissions: ["meeting:create", "meeting:read"],
      },
      error: null,
      requestId: "req_03",
      traceId: "trace_03",
    });
  }),

  http.get("/api/meetings", () => {
    return HttpResponse.json<ApiResponse<Meeting[]>>({
      success: true,
      data: meetingList,
      error: null,
      requestId: "req_04",
      traceId: "trace_04",
    });
  }),

  http.post("/api/meetings", () => {
    return HttpResponse.json<ApiResponse<Meeting>>({
      success: true,
      data: meetingList[0]!,
      error: null,
      requestId: "req_05",
      traceId: "trace_05",
    });
  }),

  http.get("/api/meetings/:meetingId", ({ params }) => {
    const meeting = meetingList.find((item) => item.meetingId === params.meetingId) ?? meetingList[0]!;
    return HttpResponse.json<ApiResponse<Meeting>>({
      success: true,
      data: meeting,
      error: null,
      requestId: "req_06",
      traceId: "trace_06",
    });
  }),

  http.post("/api/meetings/:meetingId/processing-tasks", ({ params }) => {
    return HttpResponse.json<ApiResponse<ProcessingTask>>({
      success: true,
      data: mockTask(String(params.meetingId)),
      error: null,
      requestId: "req_07",
      traceId: "trace_07",
    });
  }),

  http.get("/api/meetings/:meetingId/processing-tasks/latest", ({ params }) => {
    return HttpResponse.json<ApiResponse<ProcessingTask>>({
      success: true,
      data: mockTask(String(params.meetingId)),
      error: null,
      requestId: "req_latest",
      traceId: "trace_latest",
    });
  }),

  http.get("/api/processing-tasks/:taskId", () => {
    return HttpResponse.json<ApiResponse<ProcessingTask>>({
      success: true,
      data: mockTask("mtg_01"),
      error: null,
      requestId: "req_08",
      traceId: "trace_08",
    });
  }),

  http.post("/api/meetings/:meetingId/files/audio/uploads", ({ params }) => {
    return HttpResponse.json<ApiResponse<AudioUploadSession>>({
      success: true,
      data: { ...uploadSession, meetingId: String(params.meetingId) },
      error: null,
      requestId: "req_upload_01",
      traceId: "trace_upload_01",
    });
  }),

  http.post("/api/meetings/:meetingId/files/audio/uploads/:uploadId/parts", async ({ request }) => {
    const body = await request.json() as { partNumber: number; partSha256: string };
    return HttpResponse.json<ApiResponse<{
      uploadId: string;
      partNumber: number;
      partSha256: string;
      etag?: string | null;
      uploadUrl: string;
      expiresAt: string;
      headers: Record<string, string>;
    }>>({
      success: true,
      data: {
        uploadId: "upl_01",
        partNumber: body.partNumber,
        partSha256: body.partSha256,
        etag: `etag_${body.partNumber}`,
        uploadUrl: `http://localhost/upload/part/${body.partNumber}`,
        expiresAt: "2026-05-14T10:15:00Z",
        headers: { "Content-Type": "audio/wav" },
      },
      error: null,
      requestId: "req_upload_part",
      traceId: "trace_upload_part",
    });
  }),

  http.put("http://localhost/upload/part/:partNumber", ({ params }) => {
    return new HttpResponse(null, {
      status: 200,
      headers: { ETag: `etag_${String(params.partNumber)}` },
    });
  }),

  http.post("/api/meetings/:meetingId/files/audio/uploads/:uploadId/complete", ({ params }) => {
    return HttpResponse.json<ApiResponse<AudioUploadSession>>({
      success: true,
      data: {
        ...uploadSession,
        meetingId: String(params.meetingId),
        uploadId: String(params.uploadId),
        uploadStatus: "COMPLETED",
        fileId: "file_01",
      },
      error: null,
      requestId: "req_upload_complete",
      traceId: "trace_upload_complete",
    });
  }),

  http.post("/api/meetings/:meetingId/files/audio/uploads/:uploadId/abort", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: null,
      error: null,
      requestId: "req_upload_abort",
      traceId: "trace_upload_abort",
    });
  }),

  http.get("/api/meetings/:meetingId/files/audio/uploads/:uploadId", ({ params }) => {
    return HttpResponse.json<ApiResponse<AudioUploadSession>>({
      success: true,
      data: { ...uploadSession, meetingId: String(params.meetingId), uploadId: String(params.uploadId) },
      error: null,
      requestId: "req_upload_get",
      traceId: "trace_upload_get",
    });
  }),

  http.get("/api/meetings/:meetingId/transcript", ({ params }) => {
    return HttpResponse.json<ApiResponse<TranscriptData>>({
      success: true,
      data: { ...transcript, meetingId: String(params.meetingId) },
      error: null,
      requestId: "req_transcript",
      traceId: "trace_transcript",
    });
  }),

  http.patch("/api/meetings/:meetingId/transcript/segments/:segmentId", async ({ request, params }) => {
    const body = (await request.json()) as { editedText: string; expectedTranscriptVersion: number };
    return HttpResponse.json<ApiResponse<{
      segmentId: string;
      transcriptVersion: number;
      editStatus: string;
      downstreamStaleMarked: boolean;
    }>>({
      success: true,
      data: {
        segmentId: String(params.segmentId),
        transcriptVersion: body.expectedTranscriptVersion,
        editStatus: "EDITED",
        downstreamStaleMarked: true,
      },
      error: null,
      requestId: "req_edit_segment",
      traceId: "trace_edit_segment",
    });
  }),

  http.get("/api/meetings/:meetingId/minutes", ({ params }) => {
    return HttpResponse.json<ApiResponse<MinutesData>>({
      success: true,
      data: {
        meetingId: String(params.meetingId),
        minutesId: "min_01",
        minutesVersion: 1,
        sourceTranscriptVersion: 1,
        title: "周会纪要",
        markdown: "## 总结\n- 阶段二验收闭环\n",
        staleStatus: "ACTIVE",
        sections: [
          {
            type: "SUMMARY",
            title: "总结",
            items: [
              {
                text: "阶段二上线",
                evidence: [
                  {
                    segmentId: "seg_01",
                    startMs: 0,
                    endMs: 1800,
                    evidenceTextSnapshot: "今天先确认阶段二验收范围。",
                  },
                ],
              },
            ],
          },
        ],
      } as MinutesData,
      error: null,
      requestId: "req_minutes",
      traceId: "trace_minutes",
    });
  }),

  http.post("/api/meetings/:meetingId/minutes/regenerate", async ({ request, params }) => {
    const body = (await request.json()) as { expectedTranscriptVersion: number };
    return HttpResponse.json<ApiResponse<MinutesData>>({
      success: true,
      data: {
        meetingId: String(params.meetingId),
        minutesId: "min_02",
        minutesVersion: 2,
        sourceTranscriptVersion: body.expectedTranscriptVersion,
        title: "周会纪要（重生成）",
        markdown: "## 总结\n- 已重新生成",
        staleStatus: "ACTIVE",
        sections: [
          { type: "SUMMARY", title: "总结", items: [{ text: "纪要已重生成", evidence: [] }] },
        ],
      } as MinutesData,
      error: null,
      requestId: "req_regen_minutes",
      traceId: "trace_regen_minutes",
    });
  }),

  http.get("/api/meetings/:meetingId/action-items", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          id: "item_01",
          meetingId: "mtg_01",
          origin: "AI_EXTRACTED",
          title: "切换到 GA 后做小流量验证",
          description: "Alice 下周确认 GA 流程",
          ownerPersonId: null,
          ownerRawText: "Alice",
          priority: "P2",
          status: "OPEN",
          acceptanceStatus: "DRAFT",
          sourceTranscriptVersion: 1,
          staleStatus: "ACTIVE",
          evidence: [
            { segmentId: "seg_01", startMs: 0, endMs: 1800, evidenceTextSnapshot: "今天先确认阶段二验收范围。" },
          ],
        },
      ],
      error: null,
      requestId: "req_actions",
      traceId: "trace_actions",
    });
  }),

  http.get("/api/meetings/:meetingId/decisions", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          id: "dec_01",
          meetingId: "mtg_01",
          title: "GA 流程沿用现网验收口径",
          description: "保留现网灰度策略",
          status: "PROPOSED",
          acceptanceStatus: "DRAFT",
          sourceTranscriptVersion: 1,
          staleStatus: "ACTIVE",
          evidence: [],
        },
      ],
      error: null,
      requestId: "req_decisions",
      traceId: "trace_decisions",
    });
  }),

  http.get("/api/meetings/:meetingId/risks", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          id: "risk_01",
          meetingId: "mtg_01",
          title: "采购侧供应延迟",
          description: "上游供货商交期不稳",
          severity: "HIGH",
          status: "OPEN",
          acceptanceStatus: "DRAFT",
          sourceTranscriptVersion: 1,
          staleStatus: "ACTIVE",
          evidence: [],
        },
      ],
      error: null,
      requestId: "req_risks",
      traceId: "trace_risks",
    });
  }),

  http.post("/api/meetings/:meetingId/action-items/:itemId/accept", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: null,
      error: null,
      requestId: "req_accept",
      traceId: "trace_accept",
    });
  }),

  http.post("/api/meetings/:meetingId/action-items/:itemId/reject", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: null,
      error: null,
      requestId: "req_reject",
      traceId: "trace_reject",
    });
  }),

  http.post("/api/meetings/:meetingId/decisions/:itemId/accept", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/meetings/:meetingId/decisions/:itemId/reject", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/meetings/:meetingId/risks/:itemId/accept", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/meetings/:meetingId/risks/:itemId/reject", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.get("/api/speaker-profiles", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          speakerProfileId: "spk_alice",
          tenantId: "tenant_01",
          personId: "alice",
          displayName: "Alice 张",
          consentStatus: "ACTIVE",
          consentSource: "MEETING_INVITE",
          consentVersion: "v1",
          revokedAt: null,
          deletedAt: null,
          createdAt: "2026-05-11T09:00:00Z",
          updatedAt: "2026-05-11T09:00:00Z",
        },
      ],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.get("/api/meetings/:meetingId/speakers", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          speakerLabel: "SPEAKER_00",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "CANDIDATE",
          autoMatchScore: 0.78,
          confirmedAt: null,
          candidatePersonIds: ["alice"],
        },
      ],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/meetings/:meetingId/speakers/:speakerLabel/confirm", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/meetings/:meetingId/speakers/:speakerLabel/reject", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/speaker-profiles", () => {
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        speakerProfileId: "spk_new",
        tenantId: "tenant_01",
        personId: "bob",
        displayName: "Bob 李",
        consentStatus: "ACTIVE",
        consentSource: "USER_ENROLLMENT",
        consentVersion: "v1",
        revokedAt: null,
        deletedAt: null,
        createdAt: "2026-05-12T09:00:00Z",
        updatedAt: "2026-05-12T09:00:00Z",
      },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/speaker-profiles/:profileId/revoke", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.delete("/api/speaker-profiles/:profileId", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.get("/api/speaker-profiles/:profileId/enrollments", () => {
    return HttpResponse.json<ApiResponse<unknown[]>>({
      success: true,
      data: [
        {
          enrollmentId: "spe_01",
          speakerProfileId: "spk_alice",
          tenantId: "tenant_01",
          sourceAudioFileId: "file_01",
          enrollmentStatus: "SUCCEEDED",
          qualityScore: 0.92,
          modelVersion: "deterministic-speaker-v0",
          errorCode: null,
          createdAt: "2026-05-11T10:00:00Z",
          updatedAt: "2026-05-11T10:05:00Z",
        },
      ],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/speaker-profiles/:profileId/enrollments", () => {
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        enrollmentId: "spe_new",
        speakerProfileId: "spk_alice",
        tenantId: "tenant_01",
        sourceAudioFileId: "file_new",
        enrollmentStatus: "PENDING",
        qualityScore: null,
        modelVersion: null,
        errorCode: null,
        createdAt: "2026-05-12T11:00:00Z",
        updatedAt: "2026-05-12T11:00:00Z",
      },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  // ── Documents ────────────────────────────────────────────────────
  http.get("/api/documents", () => {
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: [
        {
          documentId: "doc_01",
          tenantId: "tenant_01",
          title: "Roadmap.pdf",
          fileId: "file_doc_01",
          documentType: "PDF",
          status: "ACTIVE",
          securityLevel: "INTERNAL",
          textExtractionStatus: "EXTRACTED",
          contentHash: "abc123",
          sourceUri: null,
          createdAt: "2026-05-12T08:00:00Z",
          updatedAt: "2026-05-12T08:00:00Z",
          deletedAt: null,
        },
      ],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/documents", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        documentId: "doc_new",
        tenantId: "tenant_01",
        title: body.title ?? "(untitled)",
        fileId: body.fileId ?? "file_unknown",
        documentType: body.documentType ?? "PDF",
        status: "ACTIVE",
        securityLevel: body.securityLevel ?? "INTERNAL",
        textExtractionStatus: "PENDING",
        contentHash: body.contentHash ?? null,
        sourceUri: null,
        createdAt: "2026-05-17T09:00:00Z",
        updatedAt: "2026-05-17T09:00:00Z",
        deletedAt: null,
      },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.delete("/api/documents/:documentId", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: null,
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/documents/:documentId/reindex", ({ params }) => {
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        documentId: params.documentId as string,
        tenantId: "tenant_01",
        title: "Roadmap.pdf",
        fileId: "file_doc_01",
        documentType: "PDF",
        status: "ACTIVE",
        securityLevel: "INTERNAL",
        textExtractionStatus: "EXTRACTED",
        contentHash: "abc123",
        sourceUri: null,
        createdAt: "2026-05-12T08:00:00Z",
        updatedAt: "2026-05-17T10:00:00Z",
        deletedAt: null,
      },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/rag/reindex/meetings/:meetingId", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/rag/reindex/documents/:documentId", () => {
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  // ── Exports (phase 6) ───────────────────────────────────────
  http.get("/api/meetings/:meetingId/exports", ({ params }) => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: { items: exportsByMeeting[String(params.meetingId)] ?? [] },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/meetings/:meetingId/exports", async ({ params, request }) => {
    const meetingId = String(params.meetingId);
    const body = (await request.json()) as {
      format: "MARKDOWN" | "DOCX" | "PDF";
      expectedTranscriptVersion: number;
      expectedMinutesVersion: number | null;
      includeTranscript: boolean;
      includeMinutes: boolean;
      includeItems: boolean;
      includeSpeakers: boolean;
      watermarkText: string | null;
    };
    if (body.expectedTranscriptVersion === -1) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: "EXPORT_CONTENT_STALE", message: "stale", retryable: false, details: {} },
          requestId: "r",
          traceId: "t",
        },
        { status: 422 },
      );
    }
    const next = {
      exportId: `exp_mock_${++exportIdCounter}`,
      meetingId,
      status: "QUEUED" as const,
      format: body.format,
      dataBoundaryMode: "FULL",
      inputTranscriptVersion: body.expectedTranscriptVersion,
      inputMinutesVersion: body.expectedMinutesVersion,
      snapshotManifestId: "mfst_mock_01",
      watermarkText: body.watermarkText,
      downloadUrl: null,
      downloadUrlExpiresAt: null,
      sha256: null,
      fileSizeBytes: null,
      revoked: false,
      stale: false,
      errorCode: null,
      expiresAt: "2026-05-19T02:00:00Z",
      createdAt: "2026-05-18T02:00:00Z",
      finishedAt: null,
    };
    exportsByMeeting[meetingId] = [next, ...(exportsByMeeting[meetingId] ?? [])];
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: next,
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/exports/:exportId/cancel", ({ params }) => {
    const id = String(params.exportId);
    for (const list of Object.values(exportsByMeeting)) {
      const idx = list.findIndex((j) => j.exportId === id);
      if (idx >= 0) {
        list[idx] = { ...list[idx]!, status: "CANCELLED", finishedAt: "2026-05-18T02:01:00Z" };
        break;
      }
    }
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  http.post("/api/exports/:exportId/revoke-link", ({ params }) => {
    const id = String(params.exportId);
    for (const list of Object.values(exportsByMeeting)) {
      const idx = list.findIndex((j) => j.exportId === id);
      if (idx >= 0) {
        list[idx] = { ...list[idx]!, status: "REVOKED", revoked: true, downloadUrl: null };
        break;
      }
    }
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  // ── Legal holds (phase 7) ───────────────────────────────────
  http.get("/api/legal-holds", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: { items: legalHolds.slice() },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/legal-holds", async ({ request }) => {
    const body = (await request.json()) as {
      scopeType: "MEETING" | "DOCUMENT" | "SPEAKER_PROFILE" | "PROJECT";
      scopeId: string;
      reason: string;
      approvedBy?: string | null;
    };
    const next = {
      legalHoldId: `lh_mock_${legalHolds.length + 1}`,
      scopeType: body.scopeType,
      scopeId: body.scopeId,
      reason: body.reason,
      status: "ACTIVE" as const,
      requestedBy: "user_compliance_mock",
      approvedBy: body.approvedBy ?? null,
      createdAt: new Date().toISOString(),
      releasedAt: null,
      releasedBy: null,
      releaseReason: null,
    };
    legalHolds.unshift(next);
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: next,
      error: null,
      requestId: "r",
      traceId: "t",
    }, { status: 201 });
  }),

  http.get("/api/legal-holds/:legalHoldId", ({ params }) => {
    const id = String(params.legalHoldId);
    const found = legalHolds.find((h) => h.legalHoldId === id);
    if (!found) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: "LEGAL_HOLD_NOT_FOUND", message: "n/a", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 404 },
      );
    }
    return HttpResponse.json<ApiResponse>({ success: true, data: found, error: null, requestId: "r", traceId: "t" });
  }),

  http.put("/api/legal-holds/:legalHoldId/release", async ({ params, request }) => {
    const id = String(params.legalHoldId);
    const body = (await request.json()) as { reason: string };
    const idx = legalHolds.findIndex((h) => h.legalHoldId === id);
    if (idx < 0) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: "LEGAL_HOLD_NOT_FOUND", message: "n/a", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 404 },
      );
    }
    legalHolds[idx] = {
      ...legalHolds[idx]!,
      status: "RELEASED",
      releasedAt: new Date().toISOString(),
      releasedBy: "user_admin_mock",
      releaseReason: body.reason,
    };
    return HttpResponse.json<ApiResponse>({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
  }),

  // ── Deletion jobs (phase 7.3) ───────────────────────────────
  http.get("/api/admin/deletion-jobs", () => {
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: { items: deletionJobs.slice() },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/admin/deletion-jobs", async ({ request }) => {
    const body = (await request.json()) as {
      scopeType: MockDeletionJob["scopeType"];
      scopeId: string;
      reason: string;
      approvedBy?: string | null;
    };
    // For the front-end tests, simulate the legal-hold check by treating
    // any scopeId containing "_protected" as blocked.
    const blocked = body.scopeId.includes("_protected");
    const now = new Date().toISOString();
    const next: MockDeletionJob = {
      deletionJobId: `dj_mock_${deletionJobs.length + 1}`,
      scopeType: body.scopeType,
      scopeId: body.scopeId,
      status: blocked ? "BLOCKED_BY_LEGAL_HOLD" : "REQUESTED",
      requestedBy: "user_compliance_mock",
      approvedBy: body.approvedBy ?? null,
      legalHoldChecked: true,
      deletedRows: {},
      deletedFiles: {},
      kmsKeysDestroyed: {},
      certificateHash: null,
      errorCode: blocked ? "DELETION_JOB_BLOCKED_BY_LEGAL_HOLD" : null,
      createdAt: now,
      finishedAt: blocked ? now : null,
    };
    deletionJobs.unshift(next);
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: next,
      error: null,
      requestId: "r",
      traceId: "t",
    }, { status: 202 });
  }),

  http.get("/api/admin/deletion-jobs/:jobId", ({ params }) => {
    const id = String(params.jobId);
    const found = deletionJobs.find((j) => j.deletionJobId === id);
    if (!found) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: "VALIDATION_FAILED", message: "not found", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 404 },
      );
    }
    return HttpResponse.json<ApiResponse>({ success: true, data: found, error: null, requestId: "r", traceId: "t" });
  }),

  // ── Break-glass (phase 7.4) ─────────────────────────────────
  http.get("/api/admin/break-glass/requests", ({ request: req }) => {
    const url = new URL(req.url);
    const status = url.searchParams.get("status");
    const items = status
      ? breakGlassRequests.filter((r) => r.status === status)
      : breakGlassRequests.slice();
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: { items },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/admin/break-glass/requests", async ({ request }) => {
    const body = (await request.json()) as {
      scopeType: string;
      scopeId: string;
      reason: string;
    };
    const now = new Date().toISOString();
    const next: MockBreakGlassRequest = {
      breakGlassRequestId: `bg_mock_${breakGlassRequests.length + 1}`,
      requesterId: "user_requester_mock",
      scopeType: body.scopeType,
      scopeId: body.scopeId,
      reason: body.reason,
      status: "PENDING",
      validFrom: null,
      validUntil: null,
      approverId: null,
      approvedAt: null,
      rejectedAt: null,
      rejectReason: null,
      revokedAt: null,
      revokedBy: null,
      createdAt: now,
    };
    breakGlassRequests.unshift(next);
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: next,
      error: null,
      requestId: "r",
      traceId: "t",
    }, { status: 201 });
  }),

  http.post("/api/admin/break-glass/requests/:requestId/approve", ({ params }) => {
    const id = String(params.requestId);
    const idx = breakGlassRequests.findIndex((r) => r.breakGlassRequestId === id);
    if (idx < 0) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: "VALIDATION_FAILED", message: "not found", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 404 },
      );
    }
    const now = new Date();
    const validUntil = new Date(now.getTime() + 4 * 60 * 60 * 1000);
    breakGlassRequests[idx] = {
      ...breakGlassRequests[idx]!,
      status: "APPROVED",
      approverId: "user_admin_mock",
      approvedAt: now.toISOString(),
      validFrom: now.toISOString(),
      validUntil: validUntil.toISOString(),
    };
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: breakGlassRequests[idx],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/admin/break-glass/requests/:requestId/reject", async ({ params, request }) => {
    const id = String(params.requestId);
    const body = (await request.json()) as { reason: string };
    const idx = breakGlassRequests.findIndex((r) => r.breakGlassRequestId === id);
    if (idx < 0) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: "VALIDATION_FAILED", message: "not found", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 404 },
      );
    }
    breakGlassRequests[idx] = {
      ...breakGlassRequests[idx]!,
      status: "REJECTED",
      approverId: "user_admin_mock",
      rejectedAt: new Date().toISOString(),
      rejectReason: body.reason,
    };
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: breakGlassRequests[idx],
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  // ── Audit events (phase 7.5) ────────────────────────────────
  http.get("/api/admin/audit-events", ({ request: req }) => {
    const url = new URL(req.url);
    const actorUserId = url.searchParams.get("actorUserId");
    const resourceType = url.searchParams.get("resourceType");
    const action = url.searchParams.get("action");
    const result = url.searchParams.get("result");

    const matches = mockAuditEvents.filter((e) => {
      if (actorUserId && e.actorUserId !== actorUserId) return false;
      if (resourceType && e.resourceType !== resourceType) return false;
      if (action && e.action !== action) return false;
      if (result && e.result !== result) return false;
      return true;
    });
    return HttpResponse.json<ApiResponse>({
      success: true,
      data: { items: matches },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),

  http.post("/api/rag/query", async ({ request }) => {
    const body = (await request.json()) as { question?: string };
    const q = body.question ?? "";
    const isEmpty = q.includes("(empty)");
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        answer: isEmpty ? "根据现有信息无法回答。" : `基于检索到的片段回答：${q}`,
        citations: isEmpty
          ? []
          : [
              {
                type: "MEETING_SEGMENT",
                meetingId: "mtg_01",
                meetingTitle: "产品周会",
                segmentId: "seg_01",
                speaker: "Alice",
                startMs: 12000,
                endMs: 25000,
                content: "我们决定下周完成路线图初稿。",
              },
              {
                type: "DOCUMENT_CHUNK",
                documentId: "doc_01",
                documentTitle: "Roadmap.pdf",
                chunkId: "ck_doc_1",
                page: 3,
                content: "Q3 路线图重点关注客户旅程。",
              },
            ],
        coverage: isEmpty ? "TRANSCRIPT_ONLY" : "FULL",
        artifactManifestId: "llmlog_demo",
      },
      error: null,
      requestId: "r",
      traceId: "t",
    });
  }),
];

function mockTask(meetingId: string): ProcessingTask {
  return {
    taskId: "task_01",
    meetingId,
    status: "RUNNING",
    phase: "WORKER_DAG_RUNNING",
    attemptNo: 1,
    currentStep: "ASR",
    lastErrorCode: null,
    retryable: false,
    steps: [
      { stepName: "AUDIO_UPLOAD", status: "SUCCEEDED", progress: 100, source: "JAVA_TASK_SERVICE" },
      { stepName: "AUDIO_PREPROCESS", status: "SUCCEEDED", progress: 100, source: "AI_WORKER_CALLBACK", attemptNo: 1 },
      { stepName: "ASR", status: "RUNNING", progress: 50, source: "AI_WORKER_CALLBACK", attemptNo: 1 },
    ],
  };
}
