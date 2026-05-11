// ── API Response Envelope ──────────────────────────────────────────
// One-off types until codegen from public-api.yaml is running.
// Generated types will replace these once `npm run codegen` is configured.

export interface ApiResponse<T = unknown> {
  success: boolean;
  data: T | null;
  error: ApiError | null;
  requestId: string;
  traceId: string;
}

export interface ApiError {
  code: string;
  message: string;
  retryable: boolean;
  details?: Record<string, unknown>;
}

export interface Page<T> {
  items: T[];
  page: {
    cursor: string | null;
    hasMore: boolean;
    limit: number;
  };
}

// ── Meeting ──────────────────────────────────────────────────────

export type SecurityLevel = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET";

export interface Meeting {
  meetingId: string;
  tenantId: string;
  title: string;
  securityLevel: SecurityLevel;
  status: string;
  language: string;
  transcriptVersion: number;
  minutesVersion: number;
  createdAt: string;
}

export interface CreateMeetingRequest {
  title: string;
  securityLevel: SecurityLevel;
  scheduledStartAt?: string;
  language?: string;
  participants?: {
    personId: string;
    displayName: string;
    role: string;
  }[];
}

// ── Task ─────────────────────────────────────────────────────────

export type ProcessingTaskStatus =
  | "PENDING"
  | "QUEUED"
  | "RUNNING"
  | "ORPHANED"
  | "PARTIAL_SUCCEEDED"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCEL_PENDING"
  | "CANCELLED";

export type ProcessingTaskPhase =
  | "WORKER_DAG_RUNNING"
  | "WORKER_DAG_DONE"
  | "JAVA_LLM_RUNNING"
  | "TERMINAL";

export type ProcessingStepUpdateSource = "JAVA_TASK_SERVICE" | "AI_WORKER_CALLBACK";

export interface ProcessingTask {
  taskId: string;
  meetingId: string;
  status: ProcessingTaskStatus;
  phase: ProcessingTaskPhase;
  attemptNo: number;
  currentStep: string | null;
  lastErrorCode: string | null;
  retryable: boolean;
  steps: TaskStep[];
}

export interface TaskStep {
  stepName: string;
  status: string;
  progress: number;
  startedAt: string | null;
  finishedAt: string | null;
  heartbeatAt: string | null;
  attemptNo?: number | null;
  leaseOwner?: string | null;
  workerId?: string | null;
  retryable?: boolean | null;
  source: ProcessingStepUpdateSource;
}

export type TaskEventType =
  | "TASK_SNAPSHOT"
  | "TASK_STARTED"
  | "TASK_STEP_UPDATED"
  | "TASK_HEARTBEAT"
  | "TRANSCRIPT_READY"
  | "TASK_FAILED"
  | "TASK_COMPLETED"
  | "TASK_CANCELLED";

export interface TaskEvent {
  eventId: string;
  sequenceNo: number;
  eventType: TaskEventType;
  taskId: string;
  meetingId?: string;
  stepName?: string;
  status: string;
  phase?: ProcessingTaskPhase | null;
  progress?: number;
  retryable?: boolean;
  errorCode?: string | null;
  emittedAt: string;
  attemptNo?: number;
  transcriptVersion?: number;
  artifactManifestId?: string | null;
  completedSteps?: string[];
  leaseExpiresAt?: string;
}

// ── Transcript ───────────────────────────────────────────────────

export interface TranscriptSegment {
  segmentId: string;
  startMs: number;
  endMs: number;
  speakerLabel: string;
  speakerDisplayName?: string;
  originalText: string;
  editedText: string | null;
  currentText: string;
  asrConfidence: number;
  diarizationConfidence: number;
  timestampPrecision: "WORD" | "SEGMENT" | "APPROXIMATE";
}

export interface TranscriptData {
  meetingId: string;
  transcriptVersion: number;
  staleStatus: string;
  segments: TranscriptSegment[];
}

// ── Minutes ──────────────────────────────────────────────────────

export interface MinutesData {
  meetingId: string;
  minutesId: string;
  minutesVersion: number;
  staleStatus: string;
  markdown: string;
  sections: MinutesSection[];
  artifactManifestId: string;
}

export interface MinutesSection {
  type: string;
  title: string;
  items: MinutesItem[];
}

export interface MinutesItem {
  text: string;
  evidence: Evidence[];
  assigneeDisplayName?: string;
  dueDate?: string;
  severity?: "HIGH" | "MEDIUM" | "LOW";
}

export interface Evidence {
  segmentId: string;
  startMs: number;
  endMs: number;
  evidenceTextSnapshot: string;
}

// ── RAG ──────────────────────────────────────────────────────────

export type RagAnswerCoverage = "TRANSCRIPT_ONLY" | "FULL";

export interface RagQueryRequest {
  query: string;
  scope?: {
    meetingIds?: string[];
    documentIds?: string[];
  };
  topK?: number;
  includeCitations?: boolean;
}

export interface RagQueryResponse {
  answer: string;
  citations: Citation[];
  coverage: RagAnswerCoverage;
  artifactManifestId: string;
}

export type Citation =
  | MeetingSegmentCitation
  | DocumentChunkCitation;

export interface MeetingSegmentCitation {
  type: "MEETING_SEGMENT";
  meetingId: string;
  meetingTitle: string;
  segmentId: string;
  speaker: string;
  startMs: number;
  endMs: number;
  content: string;
}

export interface DocumentChunkCitation {
  type: "DOCUMENT_CHUNK";
  documentId: string;
  documentTitle: string;
  chunkId: string;
  page: number;
  content: string;
}

// ── Auth ─────────────────────────────────────────────────────────

export interface AuthUser {
  userId: string;
  tenantId: string;
  personId?: string;
  displayName: string;
  roles: string[];
  permissions: string[];
}
