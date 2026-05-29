/**
 * Hand-written admin BFF DTO surfaces. The OpenAPI codegen target is
 * `npm run codegen` (points at the public-api spec); this file covers the
 * worker BFF responses + a few public-API shapes the workstation needs.
 */

export type DocumentRole = "REFERENCE" | "ATTACHMENT";
export type SecurityLevel = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "SECRET";
export type ProcessingTaskPhase = "WORKER_DAG_RUNNING" | "WORKER_DAG_DONE" | "JAVA_LLM_RUNNING" | "TERMINAL";
export type ProcessingTaskStatus =
  | "PENDING" | "QUEUED" | "RUNNING" | "ORPHANED" | "PARTIAL_SUCCEEDED"
  | "SUCCEEDED" | "FAILED" | "CANCEL_PENDING" | "CANCELLED";
export type StepStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "SKIPPED";
export type DocumentType = "PDF" | "DOCX" | "PPTX" | "TXT" | "MD" | "OTHER";

export interface PersonDTO {
  personId: string;
  displayName: string;
  email: string | null;
  externalId: string | null;
  createdAt: string;
  /** Legacy BFF shape kept optional while older fixtures still return id. */
  id?: string;
}

export interface CreatePersonRequest {
  displayName: string;
  email?: string;
  externalId?: string;
  forceCreate?: boolean;
}

export interface PersonDuplicateError {
  code: "PERSON_DUPLICATE";
  message: string;
  retryable: false;
  details: { matches: PersonDTO[] };
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
  documentType?: DocumentType | string;
  contentHash?: string | null;
  createdAt?: string;
}

export interface CreateDocumentRequest {
  title: string;
  fileId: string;
  documentType: DocumentType | string;
  securityLevel: SecurityLevel;
  contentHash: string;
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
  speakerLabel: string;
  displayName: string | null;
  personId: string | null;
  speakerProfileId: string | null;
  confirmationStatus: "UNCONFIRMED" | "AUTO_CONFIRMED" | "MANUALLY_CONFIRMED" | "REJECTED" | string;
  autoMatchScore: number | null;
  confirmedAt: string | null;
  candidatePersonIds: string[];
  label?: string;
  verificationStatus?: "CANDIDATE" | "CONFIRMED" | "REJECTED" | "MANUAL" | string;
  candidates?: SpeakerCandidateDTO[];
}

export interface TranscriptSegmentDTO {
  segmentId: string;
  speakerLabel: string;
  speakerName: string | null;
  startMs: number;
  endMs: number;
  text: string;
}

export interface ProcessingTaskStepDTO {
  stepName: string;
  status: StepStatus | string;
  progress: number;
  retryable?: boolean | null;
  errorCode?: string | null;
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
  steps?: ProcessingTaskStepDTO[];
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

export interface FileUploadPartDTO {
  partNumber: number;
  partSha256?: string;
  sizeBytes?: number;
  etag?: string | null;
  uploadUrl?: string;
  presignedUrl?: string;
  expiresAt: string;
  headers?: Record<string, string>;
}

export interface FileUploadSessionDTO {
  uploadId: string;
  expiresAt?: string;
  partSizeBytes?: number;
  maxPartCount?: number;
  objectKey?: string | null;
  bucket?: string | null;
  contentType?: string;
  fileName?: string;
  fileSizeBytes?: number;
  fileSha256?: string;
  fileId?: string | null;
  parts: FileUploadPartDTO[];
}

export interface FileUploadCompleteResponseDTO {
  fileId: string;
  sha256: string;
  sizeBytes: number;
  contentType: string;
}

export interface AudioUploadSessionDTO {
  uploadId: string;
  meetingId?: string;
  uploadStatus?: string;
  status?: string;
  expiresAt?: string;
  partSizeBytes?: number;
  maxPartCount?: number;
  objectKey?: string;
  bucket?: string;
  contentType?: string;
  fileName?: string;
  fileSizeBytes?: number;
  fileSha256?: string;
  fileId?: string | null;
  parts: FileUploadPartDTO[];
}

export interface TaskEventDTO {
  eventId?: string;
  sequenceNo?: number;
  eventType?: string;
  taskId: string;
  meetingId?: string | null;
  stepName?: string | null;
  status?: string;
  phase?: ProcessingTaskPhase;
  progress?: number | null;
  retryable?: boolean | null;
  errorCode?: string | null;
  attemptNo?: number | null;
  completedSteps?: string[];
  steps?: ProcessingTaskStepDTO[];
}
