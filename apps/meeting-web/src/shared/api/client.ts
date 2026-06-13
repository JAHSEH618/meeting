// API client — base fetch wrapper with auth token, envelope unwrap, error handling.
// Attaches X-Request-Id, X-Trace-Id, and Idempotency-Key on mutating requests.
//
// SECURITY NOTE (phase 4): None of the response DTOs in this file carry plaintext
// speaker embeddings, KMS-wrapped data keys, or artifactManifestId. If a future
// endpoint accidentally returns one of these, the safety net in
// shared/utils/sensitive-fields.ts redacts the field before any logging path
// can surface it — but keep API responses clean first.

import type { ApiResponse, ApiError, TaskEvent } from "@shared/api/types";

const API_BASE = "/api";

let authToken: string | null = null;
let refreshPromise: Promise<{ accessToken: string; expiresAt: string }> | null = null;

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

function normalizeSpeakerProfile(profile: SpeakerProfile): SpeakerProfile {
  return {
    ...profile,
    consentStatus: profile.consentStatus ?? profile.status ?? "UNKNOWN",
    revokedAt: profile.revokedAt ?? null,
  };
}

async function handleUnauthorized<T>(
  originalMethod: string,
  originalPath: string,
  originalBody?: unknown,
  originalIdempotencyKey?: string,
): Promise<T> {
  // Single-flight pattern: if refresh already in-flight, await it
  if (refreshPromise) {
    try {
      const result = await refreshPromise;
      setAuthToken(result.accessToken);
      // Retry original request with new token
      return request<T>(originalMethod, originalPath, originalBody, originalIdempotencyKey);
    } catch {
      // Refresh failed, clear state
      refreshPromise = null;
      setAuthToken(null);
      const error = new Error("认证已过期，请重新登录") as ApiClientError;
      error.code = "AUTH_REQUIRED";
      error.retryable = false;
      throw error;
    }
  }

  // Start new refresh
  refreshPromise = refresh();
  try {
    const result = await refreshPromise;
    setAuthToken(result.accessToken);
    refreshPromise = null;
    // Retry original request with new token
    return request<T>(originalMethod, originalPath, originalBody, originalIdempotencyKey);
  } catch {
    // Refresh failed, clear state
    refreshPromise = null;
    setAuthToken(null);
    const error = new Error("认证已过期，请重新登录") as ApiClientError;
    error.code = "AUTH_REQUIRED";
    error.retryable = false;
    throw error;
  }
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

  // Intercept 401 for token refresh
  if (res.status === 401 && authToken) {
    return handleUnauthorized<T>(method, path, body, idempotencyKey);
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

async function uploadBinary(
  url: string,
  body: Blob,
  headers: Record<string, string>,
): Promise<{ etag: string }> {
  let res: Response;
  try {
    res = await fetch(url, {
      method: "PUT",
      headers,
      body,
    });
  } catch (cause) {
    const error = new Error("网络连接失败") as ApiClientError;
    error.code = "DEPENDENCY_UNAVAILABLE";
    error.retryable = true;
    error.details = { cause: String(cause) };
    throw error;
  }

  if (!res.ok) {
    const error = new Error(`上传分片失败: ${res.status}`) as ApiClientError;
    error.code = "OSS_WRITE_FAILED";
    error.retryable = res.status >= 500;
    error.status = res.status;
    throw error;
  }

  return { etag: res.headers.get("ETag")?.replaceAll('"', "") || `etag_${Date.now()}` };
}

// ── Auth ───────────────────────────────────────────────────────────

export async function login(username: string, password: string) {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    "X-Request-Id": generateId("req"),
    "X-Trace-Id": generateId("trace"),
  };

  let res: Response;
  try {
    res = await fetch(`${API_BASE}/auth/login`, {
      method: "POST",
      headers,
      credentials: "include",
      body: JSON.stringify({ username, password }),
    });
  } catch (cause) {
    const error = new Error("网络连接失败") as ApiClientError;
    error.code = "DEPENDENCY_UNAVAILABLE";
    error.retryable = true;
    error.details = { cause: String(cause) };
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

  return json.data as { accessToken: string; expiresAt: string; user: import("@shared/api/types").AuthUser };
}

export async function logout() {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    "X-Request-Id": generateId("req"),
    "X-Trace-Id": generateId("trace"),
  };

  if (authToken) {
    headers["Authorization"] = `Bearer ${authToken}`;
  }

  await fetch(`${API_BASE}/auth/logout`, {
    method: "POST",
    headers,
    credentials: "include",
  });
}

export async function refresh() {
  // Read CSRF token from cookie
  const csrfToken = document.cookie
    .split("; ")
    .find((row) => row.startsWith("XSRF-TOKEN="))
    ?.split("=")[1];

  if (!csrfToken) {
    const error = new Error("CSRF token not found") as ApiClientError;
    error.code = "CSRF_TOKEN_INVALID";
    error.retryable = false;
    throw error;
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    "X-Request-Id": generateId("req"),
    "X-Trace-Id": generateId("trace"),
    "X-CSRF-Token": csrfToken,
  };

  let res: Response;
  try {
    res = await fetch(`${API_BASE}/auth/refresh`, {
      method: "POST",
      headers,
      credentials: "include",
    });
  } catch (cause) {
    const error = new Error("网络连接失败") as ApiClientError;
    error.code = "DEPENDENCY_UNAVAILABLE";
    error.retryable = true;
    error.details = { cause: String(cause) };
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

  return json.data as { accessToken: string; expiresAt: string };
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

// ── Audio Upload ──────────────────────────────────────────────────

export async function createAudioUpload(
  meetingId: string,
  data: import("@shared/api/types").CreateAudioUploadRequest,
) {
  return request<import("@shared/api/types").AudioUploadSession>(
    "POST",
    `/meetings/${meetingId}/files/audio/uploads`,
    data,
    generateId("create-upload"),
  );
}

export async function createAudioUploadPart(
  meetingId: string,
  uploadId: string,
  data: import("@shared/api/types").CreateAudioUploadPartRequest,
) {
  return request<{
    uploadId: string;
    partNumber: number;
    partSha256: string;
    etag?: string | null;
    uploadUrl: string;
    expiresAt: string;
    headers: Record<string, string>;
  }>(
    "POST",
    `/meetings/${meetingId}/files/audio/uploads/${uploadId}/parts`,
    data,
    generateId(`upload-part-${data.partNumber}`),
  );
}

export async function putAudioUploadPart(uploadUrl: string, part: Blob, headers: Record<string, string>) {
  return uploadBinary(uploadUrl, part, headers);
}

export async function completeAudioUpload(
  meetingId: string,
  uploadId: string,
  data: import("@shared/api/types").CompleteAudioUploadRequest,
) {
  return request<import("@shared/api/types").AudioUploadSession>(
    "POST",
    `/meetings/${meetingId}/files/audio/uploads/${uploadId}/complete`,
    data,
    generateId("complete-upload"),
  );
}

export async function abortAudioUpload(meetingId: string, uploadId: string) {
  return request<void>(
    "POST",
    `/meetings/${meetingId}/files/audio/uploads/${uploadId}/abort`,
    undefined,
    generateId("abort-upload"),
  );
}

export async function getAudioUpload(meetingId: string, uploadId: string) {
  return request<import("@shared/api/types").AudioUploadSession>(
    "GET",
    `/meetings/${meetingId}/files/audio/uploads/${uploadId}`,
  );
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

export async function getLatestMeetingTask(meetingId: string) {
  return request<import("@shared/api/types").ProcessingTask>("GET", `/meetings/${meetingId}/processing-tasks/latest`);
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
  editReason?: string | null,
) {
  return request<{
    segmentId: string;
    transcriptVersion: number;
    editStatus: string;
    downstreamStaleMarked: boolean;
  }>(
    "PATCH",
    `/meetings/${meetingId}/transcript/segments/${segmentId}`,
    { expectedTranscriptVersion, editedText, editReason: editReason ?? null },
    generateId("edit-segment"),
  );
}

// ── Minutes ────────────────────────────────────────────────────────

export async function getMinutes(meetingId: string) {
  return request<import("@shared/api/types").MinutesData>("GET", `/meetings/${meetingId}/minutes`);
}

export async function regenerateMinutes(
  meetingId: string,
  expectedTranscriptVersion: number,
  expectedMinutesVersion?: number | null,
) {
  return request<import("@shared/api/types").MinutesData>(
    "POST",
    `/meetings/${meetingId}/minutes/regenerate`,
    { expectedTranscriptVersion, expectedMinutesVersion: expectedMinutesVersion ?? null },
    generateId("regen-minutes"),
  );
}

// ── Items: action items / decisions / risks ────────────────────────

export interface ItemEvidence {
  segmentId?: string | null;
  startMs?: number | null;
  endMs?: number | null;
  evidenceTextSnapshot?: string | null;
}

export interface ActionItem {
  id: string;
  meetingId: string;
  title: string;
  description?: string | null;
  ownerPersonId?: string | null;
  ownerRawText?: string | null;
  priority?: string | null;
  status: string;
  acceptanceStatus: string;
  sourceTranscriptVersion?: number | null;
  staleStatus: string;
  evidence: ItemEvidence[];
}

export interface Decision {
  id: string;
  meetingId: string;
  title: string;
  description?: string | null;
  status: string;
  acceptanceStatus: string;
  sourceTranscriptVersion?: number | null;
  staleStatus: string;
  evidence: ItemEvidence[];
}

export interface Risk {
  id: string;
  meetingId: string;
  title: string;
  description?: string | null;
  severity?: string | null;
  status: string;
  acceptanceStatus: string;
  sourceTranscriptVersion?: number | null;
  staleStatus: string;
  evidence: ItemEvidence[];
}

export async function listActionItems(meetingId: string) {
  return request<{ items: ActionItem[] }>("GET", `/meetings/${meetingId}/action-items`);
}

export async function listDecisions(meetingId: string) {
  return request<{ items: Decision[] }>("GET", `/meetings/${meetingId}/decisions`);
}

export async function listRisks(meetingId: string) {
  return request<{ items: Risk[] }>("GET", `/meetings/${meetingId}/risks`);
}

export type ItemKind = "action-items" | "decisions" | "risks";

export async function acceptItem(meetingId: string, kind: ItemKind, itemId: string) {
  return request<void>("POST", `/meetings/${meetingId}/${kind}/${itemId}/accept`, {}, generateId(`accept-${kind}`));
}

export async function rejectItem(meetingId: string, kind: ItemKind, itemId: string) {
  return request<void>("POST", `/meetings/${meetingId}/${kind}/${itemId}/reject`, {}, generateId(`reject-${kind}`));
}

// ── Speaker profiles ───────────────────────────────────────────────

export interface SpeakerProfile {
  speakerProfileId: string;
  tenantId?: string;
  personId: string;
  displayName: string | null;
  consentStatus: string;
  status?: string;
  enrollmentCount?: number | null;
  lastEnrolledAt?: string | null;
  consentSource?: string | null;
  consentVersion?: string | null;
  revokedAt?: string | null;
  deletedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface SpeakerEnrollment {
  enrollmentId: string;
  speakerProfileId: string;
  tenantId: string;
  sourceAudioFileId: string;
  enrollmentStatus: string;
  qualityScore?: number | null;
  modelVersion?: string | null;
  errorCode?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MeetingSpeaker {
  speakerLabel: string;
  displayName: string | null;
  personId: string | null;
  speakerProfileId: string | null;
  confirmationStatus: string;
  candidates: MeetingSpeakerCandidate[];
}

export interface MeetingSpeakerCandidate {
  personId: string;
  speakerProfileId: string;
  displayName: string;
  confidence: number;
}

export interface MeetingSpeakerList {
  meetingId: string;
  speakers: MeetingSpeaker[];
}

export async function listSpeakerProfiles() {
  const page = await request<{ items: SpeakerProfile[]; page?: { cursor?: string | null; hasMore?: boolean; limit?: number } }>(
    "GET",
    `/speaker-profiles`,
  );
  return {
    ...page,
    items: page.items.map(normalizeSpeakerProfile),
  };
}

export async function getSpeakerProfile(profileId: string) {
  return request<SpeakerProfile>("GET", `/speaker-profiles/${profileId}`);
}

export async function createSpeakerProfile(input: {
  personId: string;
  displayName: string;
  consentReference?: string;
}) {
  return request<SpeakerProfile>(
    "POST",
    "/speaker-profiles",
    {
      personId: input.personId,
      displayName: input.displayName,
      consentReference: input.consentReference ?? "USER_ENROLLMENT:v1",
    },
    generateId("create-speaker-profile"),
  );
}

export async function revokeSpeakerProfile(profileId: string, reason?: string) {
  return request<void>(
    "POST",
    `/speaker-profiles/${profileId}/revoke`,
    { reason: reason ?? "user_request" },
    generateId("revoke-speaker-profile"),
  );
}

export async function deleteSpeakerProfile(profileId: string) {
  return request<void>("DELETE", `/speaker-profiles/${profileId}`);
}

export async function listSpeakerEnrollments(profileId: string) {
  return request<{ items: SpeakerEnrollment[] }>("GET", `/speaker-profiles/${profileId}/enrollments`);
}

export async function createSpeakerEnrollment(profileId: string, audioFileId: string) {
  return request<SpeakerEnrollment>(
    "POST",
    `/speaker-profiles/${profileId}/enrollments`,
    { audioFileId, consentReference: "USER_ENROLLMENT:v1" },
    generateId("create-speaker-enrollment"),
  );
}

// ── Meeting speakers (per-meeting label confirmation) ──────────────

export async function listMeetingSpeakers(meetingId: string) {
  return request<MeetingSpeakerList>("GET", `/meetings/${meetingId}/speakers`);
}

export async function confirmMeetingSpeaker(
  meetingId: string,
  speakerLabel: string,
  body: { personId: string; speakerProfileId: string; expectedTranscriptVersion: number },
) {
  return request<void>(
    "POST",
    `/meetings/${meetingId}/speakers/${encodeURIComponent(speakerLabel)}/confirm`,
    {
      personId: body.personId,
      speakerProfileId: body.speakerProfileId,
      expectedTranscriptVersion: body.expectedTranscriptVersion,
    },
    generateId("confirm-meeting-speaker"),
  );
}

export async function rejectMeetingSpeaker(meetingId: string, speakerLabel: string) {
  return request<void>(
    "POST",
    `/meetings/${meetingId}/speakers/${encodeURIComponent(speakerLabel)}/reject`,
    { reason: "user_rejected" },
    generateId("reject-meeting-speaker"),
  );
}

// ── RAG ────────────────────────────────────────────────────────────

export async function ragQuery(data: import("@shared/api/types").RagQueryRequest) {
  return request<import("@shared/api/types").RagQueryResponse>("POST", "/rag/query", data);
}

export async function reindexMeetingRag(meetingId: string) {
  return request<void>("POST", `/rag/reindex/meetings/${meetingId}`, {}, generateId("reindex-meeting-rag"));
}

export async function reindexDocumentRag(documentId: string) {
  return request<void>("POST", `/rag/reindex/documents/${documentId}`, {}, generateId("reindex-document-rag"));
}

// ── Documents ──────────────────────────────────────────────────────

export async function listDocuments() {
  return request<{ items: import("@shared/api/types").Document[] }>("GET", "/documents");
}

export async function getDocument(documentId: string) {
  return request<import("@shared/api/types").Document>("GET", `/documents/${documentId}`);
}

export async function createDocument(input: import("@shared/api/types").CreateDocumentRequest) {
  return request<import("@shared/api/types").Document>(
    "POST",
    "/documents",
    input,
    generateId("create-document"),
  );
}

export async function deleteDocument(documentId: string) {
  return request<void>("DELETE", `/documents/${documentId}`);
}

export async function reindexDocument(documentId: string) {
  return request<import("@shared/api/types").Document>(
    "POST",
    `/documents/${documentId}/reindex`,
    {},
    generateId("reindex-document"),
  );
}

// ── Exports ───────────────────────────────────────────────────────

export type ExportFormat = "MARKDOWN" | "DOCX" | "PDF";
export type ExportStatus =
  | "QUEUED"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "REVOKED";

export interface ExportJob {
  exportId: string;
  meetingId: string;
  status: ExportStatus;
  format: ExportFormat;
  dataBoundaryMode?: "FULL" | "REDACTED" | null;
  inputTranscriptVersion?: number | null;
  inputMinutesVersion?: number | null;
  snapshotManifestId?: string | null;
  watermarkText?: string | null;
  downloadUrl?: string | null;
  downloadUrlExpiresAt?: string | null;
  sha256?: string | null;
  fileSizeBytes?: number | null;
  revoked: boolean;
  stale: boolean;
  errorCode?: string | null;
  expiresAt: string;
  createdAt?: string | null;
  finishedAt?: string | null;
}

export interface CreateExportInput {
  format: ExportFormat;
  expectedTranscriptVersion: number;
  expectedMinutesVersion?: number | null;
  includeTranscript?: boolean;
  includeMinutes?: boolean;
  includeItems?: boolean;
  includeSpeakers?: boolean;
  watermarkText?: string | null;
}

export async function listMeetingExports(meetingId: string) {
  return request<{ items: ExportJob[]; page?: { cursor?: string | null; hasMore?: boolean } }>(
    "GET",
    `/meetings/${meetingId}/exports`,
  );
}

export async function createExport(meetingId: string, input: CreateExportInput) {
  return request<ExportJob>(
    "POST",
    `/meetings/${meetingId}/exports`,
    input,
    generateId("create-export"),
  );
}

export async function getExport(exportId: string) {
  return request<ExportJob>("GET", `/exports/${exportId}`);
}

export async function cancelExport(exportId: string) {
  return request<void>(
    "POST",
    `/exports/${exportId}/cancel`,
    {},
    generateId("cancel-export"),
  );
}

export async function revokeExportLink(exportId: string) {
  return request<void>(
    "POST",
    `/exports/${exportId}/revoke-link`,
    {},
    generateId("revoke-export"),
  );
}

// ── Legal holds (Phase 7) ─────────────────────────────────────────

export type LegalHoldStatus = "ACTIVE" | "RELEASED";
export type LegalHoldScopeType =
  | "MEETING"
  | "DOCUMENT"
  | "SPEAKER_PROFILE"
  | "PROJECT";

export interface LegalHold {
  legalHoldId: string;
  scopeType: LegalHoldScopeType;
  scopeId: string;
  reason: string;
  status: LegalHoldStatus;
  requestedBy: string;
  approvedBy?: string | null;
  createdAt: string;
  releasedAt?: string | null;
  releasedBy?: string | null;
  releaseReason?: string | null;
}

export interface CreateLegalHoldInput {
  scopeType: LegalHoldScopeType;
  scopeId: string;
  reason: string;
  approvedBy?: string | null;
}

export interface ReleaseLegalHoldInput {
  reason: string;
}

export async function listLegalHolds() {
  return request<{ items: LegalHold[]; page?: { cursor?: string | null; hasMore?: boolean } }>(
    "GET",
    "/legal-holds",
  );
}

export async function createLegalHold(input: CreateLegalHoldInput) {
  return request<LegalHold>(
    "POST",
    "/legal-holds",
    input,
    generateId("create-legal-hold"),
  );
}

export async function getLegalHold(legalHoldId: string) {
  return request<LegalHold>("GET", `/legal-holds/${legalHoldId}`);
}

export async function releaseLegalHold(legalHoldId: string, input: ReleaseLegalHoldInput) {
  return request<void>(
    "DELETE",
    `/legal-holds/${legalHoldId}`,
    input,
    generateId("release-legal-hold"),
  );
}

// ── Deletion jobs (Phase 7.3) ─────────────────────────────────────

export type DeletionScopeType =
  | "MEETING"
  | "DOCUMENT"
  | "SPEAKER_PROFILE"
  | "USER"
  | "PROJECT"
  | "TENANT";

export type DeletionJobStatus =
  | "REQUESTED"
  | "PENDING_APPROVAL"
  | "RUNNING"
  | "SUCCEEDED"
  | "PARTIAL_FAILED"
  | "FAILED"
  | "BLOCKED_BY_LEGAL_HOLD";

export interface DeletionJob {
  deletionJobId: string;
  scopeType: DeletionScopeType;
  scopeId: string;
  status: DeletionJobStatus;
  requestedBy: string;
  approvedBy?: string | null;
  legalHoldChecked: boolean;
  deletedRows: Record<string, unknown>;
  deletedFiles: Record<string, unknown>;
  kmsKeysDestroyed: Record<string, unknown>;
  certificateHash?: string | null;
  errorCode?: string | null;
  createdAt: string;
  finishedAt?: string | null;
}

export interface CreateDeletionJobInput {
  scopeType: DeletionScopeType;
  scopeId: string;
  reason: string;
  approvedBy?: string | null;
}

export async function listDeletionJobs() {
  return request<{ items: DeletionJob[]; page?: { cursor?: string | null; hasMore?: boolean } }>(
    "GET",
    "/admin/deletion-jobs",
  );
}

export async function createDeletionJob(input: CreateDeletionJobInput) {
  return request<DeletionJob>(
    "POST",
    "/admin/deletion-jobs",
    input,
    generateId("create-deletion-job"),
  );
}

export async function getDeletionJob(deletionJobId: string) {
  return request<DeletionJob>("GET", `/admin/deletion-jobs/${deletionJobId}`);
}

// ── Break-glass (Phase 7.4) ───────────────────────────────────────

export type BreakGlassStatus =
  | "PENDING"
  | "APPROVED"
  | "REJECTED"
  | "EXPIRED"
  | "REVOKED";

export interface BreakGlassRequestT {
  breakGlassRequestId: string;
  requesterId: string;
  scopeType: string;
  scopeId: string;
  reason: string;
  status: BreakGlassStatus;
  validFrom?: string | null;
  validUntil?: string | null;
  approverId?: string | null;
  approvedAt?: string | null;
  rejectedAt?: string | null;
  rejectReason?: string | null;
  revokedAt?: string | null;
  revokedBy?: string | null;
  createdAt: string;
}

export interface CreateBreakGlassInput {
  scopeType: string;
  scopeId: string;
  reason: string;
}

export async function listBreakGlassRequests(status?: BreakGlassStatus) {
  const q = status ? `?status=${status}` : "";
  return request<{ items: BreakGlassRequestT[]; page?: { cursor?: string | null; hasMore?: boolean } }>(
    "GET",
    `/admin/break-glass/requests${q}`,
  );
}

export async function createBreakGlassRequest(input: CreateBreakGlassInput) {
  return request<BreakGlassRequestT>(
    "POST",
    "/admin/break-glass/requests",
    input,
    generateId("create-break-glass"),
  );
}

export async function approveBreakGlassRequest(requestId: string) {
  return request<BreakGlassRequestT>(
    "POST",
    `/admin/break-glass/requests/${requestId}/approve`,
    {},
    generateId("approve-break-glass"),
  );
}

export async function rejectBreakGlassRequest(requestId: string, reason: string) {
  return request<BreakGlassRequestT>(
    "POST",
    `/admin/break-glass/requests/${requestId}/reject`,
    { reason },
    generateId("reject-break-glass"),
  );
}

// ── Audit events (Phase 7.5 query) ────────────────────────────────

export interface AuditEventT {
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

export interface AuditQueryParams {
  actorUserId?: string;
  resourceType?: string;
  resourceId?: string;
  action?: string;
  result?: string;
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}

export async function listAuditEvents(params: AuditQueryParams = {}) {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") qs.append(k, String(v));
  }
  const suffix = qs.toString() ? `?${qs.toString()}` : "";
  return request<{ items: AuditEventT[]; page?: { cursor?: string | null; hasMore?: boolean } }>(
    "GET",
    `/admin/audit-events${suffix}`,
  );
}
