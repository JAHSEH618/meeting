/**
 * Hand-written admin BFF DTO surfaces. The OpenAPI codegen target is
 * `npm run codegen` (points at the public-api spec); this file covers the
 * worker BFF responses + a few public-API shapes the wizard needs.
 */

export type DocumentRole = "REFERENCE" | "ATTACHMENT";
export type SecurityLevel = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET";
export type ProcessingTaskPhase = "WORKER_DAG_RUNNING" | "WORKER_DAG_DONE" | "JAVA_LLM_RUNNING" | "TERMINAL";
export type ProcessingTaskStatus =
  | "PENDING" | "QUEUED" | "RUNNING" | "ORPHANED" | "PARTIAL_SUCCEEDED"
  | "SUCCEEDED" | "FAILED" | "CANCEL_PENDING" | "CANCELLED";

export interface PersonDTO {
  id: string;
  displayName: string;
  email?: string;
}

export interface EnrollmentSessionDTO {
  sessionId: string;
  state: "CREATED" | "AUDIO_UPLOADED" | "PREVIEWED" | "COMMITTED";
  personId: string | null;
  qualityScore?: number;
  durationMs?: number;
  sizeBytes?: number;
}

export interface VoiceprintDTO {
  enrollmentId: string;
  personId: string;
  qualityScore: number;
  createdAt: string;
  revokedAt: string | null;
}

export interface MeetingSummaryDTO {
  meetingId: string;
  title: string;
  status: string;
  securityLevel: SecurityLevel;
  language: string;
  createdAt: string;
}

export interface DocumentSummaryDTO {
  documentId: string;
  title: string;
  securityLevel: SecurityLevel;
}

export interface MeetingDocumentItemDTO {
  id: string;
  documentId: string;
  title: string | null;
  role: DocumentRole;
  securityLevel: SecurityLevel;
  attachedBy: string | null;
  attachedAt: string;
}

export interface GlossaryTermDTO {
  term: string;
  definition?: string;
  aliases?: string[];
}

export interface MeetingGlossaryDTO {
  meetingId: string;
  terms: GlossaryTermDTO[];
  updatedAt: string | null;
}

export interface SpeakerCandidateDTO {
  personId: string;
  displayName: string;
  confidence: number;
}

export interface MeetingSpeakerDTO {
  label: string;
  displayName: string;
  verificationStatus: "CANDIDATE" | "CONFIRMED" | "REJECTED" | "MANUAL";
  candidates: SpeakerCandidateDTO[];
}

export interface TranscriptSegmentDTO {
  segmentId: string;
  speakerLabel: string;
  speakerName: string | null;
  startMs: number;
  endMs: number;
  text: string;
}

export interface ProcessingTaskDTO {
  taskId: string;
  meetingId: string | null;
  status: ProcessingTaskStatus;
  phase: ProcessingTaskPhase;
  attemptNo: number;
  currentStep: string | null;
  lastErrorCode: string | null;
  retryable: boolean;
}

export interface MeetingAggregateDTO {
  meeting: { success: boolean; data: MeetingSummaryDTO } | null;
  latestTask: { success: boolean; data: ProcessingTaskDTO } | null;
  speakers: { success: boolean; data: MeetingSpeakerDTO[] } | null;
  minutes: { success: boolean; data: { title: string; markdown: string; minutesVersion: number } } | null;
}

export interface ExportJobDTO {
  exportId: string;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "REVOKED";
  format: "MARKDOWN" | "DOCX" | "PDF";
  downloadUrl?: string | null;
  downloadExpiresAt?: string | null;
}
