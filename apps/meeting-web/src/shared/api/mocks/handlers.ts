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
