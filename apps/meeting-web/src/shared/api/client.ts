// API client — base fetch wrapper with auth token, envelope unwrap, error handling.
// Attaches X-Request-Id, X-Trace-Id, and Idempotency-Key on mutating requests.

import type { ApiResponse, ApiError } from "@shared/api/types";

const API_BASE = "/api";

let authToken: string | null = null;

export function setAuthToken(token: string | null) {
  authToken = token;
}

function generateId(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  idempotencyKey?: string,
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    "X-Request-Id": generateId("req"),
    "X-Trace-Id": generateId("trace"),
  };

  if (authToken) {
    headers["Authorization"] = `Bearer ${authToken}`;
  }

  if (idempotencyKey && method !== "GET") {
    headers["Idempotency-Key"] = idempotencyKey;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const json: ApiResponse<T> = await res.json();

  if (!json.success) {
    const err = json.error as ApiError;
    const error = new Error(err.message) as Error & {
      code: string;
      retryable: boolean;
      details?: Record<string, unknown>;
    };
    error.code = err.code;
    error.retryable = err.retryable;
    error.details = err.details;
    throw error;
  }

  return json.data as T;
}

// ── Auth ───────────────────────────────────────────────────────────

export async function login(username: string, password: string) {
  return request<{ accessToken: string; refreshToken: string; expiresAt: string; user: import("@shared/api/types").AuthUser }>(
    "POST",
    "/auth/login",
    { username, password },
  );
}

export async function logout() {
  return request<void>("POST", "/auth/logout");
}

export async function getCurrentUser() {
  return request<import("@shared/api/types").AuthUser>("GET", "/auth/me");
}

// ── Meetings ───────────────────────────────────────────────────────

export async function createMeeting(data: import("@shared/api/types").CreateMeetingRequest) {
  return request<import("@shared/api/types").Meeting>("POST", "/meetings", data, generateId("create-meeting"));
}

export async function listMeetings() {
  return request<import("@shared/api/types").Meeting[]>("GET", "/meetings");
}

export async function getMeeting(meetingId: string) {
  return request<import("@shared/api/types").Meeting>("GET", `/meetings/${meetingId}`);
}

// ── Tasks ──────────────────────────────────────────────────────────

export async function createProcessingTask(meetingId: string, audioFileId: string) {
  return request<import("@shared/api/types").ProcessingTask>(
    "POST",
    `/meetings/${meetingId}/processing-tasks`,
    { taskType: "MEETING_FULL_PIPELINE", audioFileId },
    generateId("create-task"),
  );
}

export async function getTask(taskId: string) {
  return request<import("@shared/api/types").ProcessingTask>("GET", `/processing-tasks/${taskId}`);
}

// ── Transcript ─────────────────────────────────────────────────────

export async function getTranscript(meetingId: string) {
  return request<import("@shared/api/types").TranscriptData>("GET", `/meetings/${meetingId}/transcript`);
}

export async function updateSegment(
  meetingId: string,
  segmentId: string,
  editedText: string,
  expectedTranscriptVersion: number,
) {
  return request<unknown>(
    "PATCH",
    `/meetings/${meetingId}/transcript/segments/${segmentId}`,
    { expectedTranscriptVersion, editedText },
    generateId("edit-segment"),
  );
}

// ── Minutes ────────────────────────────────────────────────────────

export async function getMinutes(meetingId: string) {
  return request<import("@shared/api/types").MinutesData>("GET", `/meetings/${meetingId}/minutes`);
}

// ── RAG ────────────────────────────────────────────────────────────

export async function ragQuery(data: import("@shared/api/types").RagQueryRequest) {
  return request<import("@shared/api/types").RagQueryResponse>("POST", "/rag/query", data);
}
