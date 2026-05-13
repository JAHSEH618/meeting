// API client — base fetch wrapper with auth token, envelope unwrap, error handling.
// Attaches X-Request-Id, X-Trace-Id, and Idempotency-Key on mutating requests.

import type { ApiResponse, ApiError, TaskEvent } from "@shared/api/types";

const API_BASE = "/api";

let authToken: string | null = null;

export function setAuthToken(token: string | null) {
  authToken = token;
}

function generateId(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}

export type ApiClientError = Error & {
  code: string;
  retryable: boolean;
  details?: Record<string, unknown>;
  status?: number;
};

function normalizeData<T>(data: unknown): T {
  if (Array.isArray(data)) {
    return { items: data, page: { hasMore: false } } as T;
  }
  return data as T;
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

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (cause) {
    const error = new Error("网络连接失败") as ApiClientError;
    error.code = "DEPENDENCY_UNAVAILABLE";
    error.retryable = true;
    error.details = { cause: String(cause) };
    throw error;
  }

  if (res.status === 404) {
    const error = new Error("资源不存在") as ApiClientError;
    error.code = "TASK_NOT_FOUND";
    error.retryable = false;
    error.status = res.status;
    throw error;
  }

  const json = (await res.json()) as ApiResponse<unknown>;

  if (!json.success) {
    const err = json.error as ApiError;
    const error = new Error(err.message) as ApiClientError;
    error.code = err.code;
    error.retryable = err.retryable;
    error.details = err.details;
    error.status = res.status;
    throw error;
  }

  return normalizeData<T>(json.data);
}

// ── Auth ───────────────────────────────────────────────────────────

export async function login(username: string, password: string) {
  return request<{ accessToken: string; expiresAt: string; user: import("@shared/api/types").AuthUser }>(
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
  return request<import("@shared/api/types").Page<import("@shared/api/types").Meeting>>("GET", "/meetings");
}

export async function getMeeting(meetingId: string) {
  return request<import("@shared/api/types").Meeting>("GET", `/meetings/${meetingId}`);
}

// ── Tasks ──────────────────────────────────────────────────────────

export async function createProcessingTask(meetingId: string, audioFileId: string) {
  return request<import("@shared/api/types").ProcessingTask>(
    "POST",
    `/meetings/${meetingId}/processing-tasks`,
    {
      taskType: "MEETING_FULL_PIPELINE",
      audioFileId,
      options: { enableAsr: true, enableDiarization: true, enableRagIndexing: true },
      expectedInputVersion: { chunkStrategyVersion: "v1" },
    },
    generateId("create-task"),
  );
}

export async function getTask(taskId: string) {
  return request<import("@shared/api/types").ProcessingTask>("GET", `/processing-tasks/${taskId}`);
}

export async function retryTask(taskId: string, reason = "user_retry") {
  return request<import("@shared/api/types").ProcessingTask>(
    "POST",
    `/processing-tasks/${taskId}/retry`,
    { reason },
    generateId("retry-task"),
  );
}

export async function cancelTask(taskId: string, reason = "user_cancel") {
  return request<import("@shared/api/types").ProcessingTask>(
    "POST",
    `/processing-tasks/${taskId}/cancel`,
    { reason },
    generateId("cancel-task"),
  );
}

export interface TaskEventSubscription {
  close: () => void;
}

export function subscribeTaskEvents(
  taskId: string,
  handlers: {
    lastEventId?: string | null;
    onEvent: (event: TaskEvent) => void;
    onFallback: () => void;
  },
): TaskEventSubscription {
  const controller = new AbortController();
  let failures = 0;

  const connect = async () => {
    while (!controller.signal.aborted && failures < 3) {
      try {
        const headers: Record<string, string> = {
          Accept: "text/event-stream",
          "X-Request-Id": generateId("req"),
          "X-Trace-Id": generateId("trace"),
        };
        if (authToken) headers.Authorization = `Bearer ${authToken}`;
        if (handlers.lastEventId) headers["Last-Event-Id"] = handlers.lastEventId;

        const response = await fetch(`${API_BASE}/processing-tasks/${taskId}/events`, {
          headers,
          signal: controller.signal,
        });
        if (!response.ok || !response.body) throw new Error(`SSE ${response.status}`);

        failures = 0;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (!controller.signal.aborted) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const chunks = buffer.split("\n\n");
          buffer = chunks.pop() ?? "";
          for (const chunk of chunks) {
            const data = chunk
              .split("\n")
              .filter((line) => line.startsWith("data:"))
              .map((line) => line.slice(5).trimStart())
              .join("\n");
            if (data) handlers.onEvent(JSON.parse(data) as TaskEvent);
          }
        }
      } catch {
        if (controller.signal.aborted) return;
        failures += 1;
      }
    }
    if (!controller.signal.aborted) handlers.onFallback();
  };

  void connect();

  return {
    close: () => controller.abort(),
  };
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
