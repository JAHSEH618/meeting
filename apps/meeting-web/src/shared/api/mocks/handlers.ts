import { http, HttpResponse } from "msw";
import type { ApiResponse, Meeting, AuthUser, ProcessingTask } from "../types";

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

  http.get("/api/processing-tasks/:taskId", () => {
    return HttpResponse.json<ApiResponse<ProcessingTask>>({
      success: true,
      data: mockTask("mtg_01"),
      error: null,
      requestId: "req_08",
      traceId: "trace_08",
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
