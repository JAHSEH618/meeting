/**
 * Workstation API types.
 *
 * Task/step/event enums and DTOs are derived from ./types.gen.ts
 * (openapi-typescript over meeting-contracts/openapi/public-api.yaml) so a
 * contract change breaks this app at compile time instead of silently at
 * runtime. Regenerate with `npm run codegen`; drift is enforced by the
 * contracts package's check-codegen-via-temp.sh.
 *
 * The remaining interfaces are the admin BFF's private response surfaces
 * (/admin/*), which have no OpenAPI spec yet and stay hand-written.
 */

import type { components } from "./types.gen";

export type DocumentRole = "REFERENCE" | "ATTACHMENT";
export type ProcessingTaskPhase = components["schemas"]["ProcessingTaskPhase"];
export type ProcessingTaskStatus = components["schemas"]["ProcessingTaskStatus"];
export type StepStatus = components["schemas"]["StepStatus"];
export type ProcessingStep = components["schemas"]["ProcessingStep"];
export type TaskEventType = components["schemas"]["TaskEvent"]["eventType"];
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
  profileId?: string | null;
  fileId?: string | null;
}

export interface SpeakerProfileDTO {
  speakerProfileId: string;
  personId: string;
  displayName: string;
  status?: string;
  enrollmentCount?: number | null;
  lastEnrolledAt?: string | null;
  consentStatus?: string;
  revokedAt: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface MeetingSummaryDTO {
  meetingId: string;
  title: string;
  scheduledStartAt?: string | null;
  status: string;
  language: string;
  transcriptVersion?: number;
  minutesVersion?: number;
  participants?: MeetingParticipantDTO[];
  createdAt: string;
}

export interface MeetingParticipantDTO {
  personId: string;
  displayName: string;
  role: string;
}

export interface DocumentSummaryDTO {
  documentId: string;
  title: string;
  documentType?: DocumentType | string;
  contentHash?: string | null;
  createdAt?: string;
}

export interface CreateDocumentRequest {
  title: string;
  fileId: string;
  documentType: DocumentType | string;
  contentHash: string;
}

export interface MeetingDocumentItemDTO {
  id: string;
  documentId: string;
  title: string | null;
  role: DocumentRole;
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
  speakerProfileId: string;
  displayName: string;
  confidence: number;
}

export interface MeetingSpeakerDTO {
  speakerLabel: string;
  displayName: string | null;
  personId: string | null;
  speakerProfileId: string | null;
  confirmationStatus: "UNCONFIRMED" | "CANDIDATE" | "AUTO_CONFIRMED" | "MANUALLY_CONFIRMED" | "CONFIRMED" | "REJECTED" | string;
  label?: string;
  verificationStatus?: "CANDIDATE" | "CONFIRMED" | "REJECTED" | "MANUAL" | string;
  candidates: SpeakerCandidateDTO[];
}

export interface MeetingSpeakerListDTO {
  meetingId: string;
  speakers: MeetingSpeakerDTO[];
}

export interface TranscriptSegmentDTO {
  segmentId: string;
  speakerLabel: string;
  speakerName: string | null;
  startMs: number;
  endMs: number;
  text: string;
}

type ContractStep = components["schemas"]["ProcessingTaskStep"];
type ContractTask = components["schemas"]["ProcessingTask"];

/**
 * UI-lenient step row. Field names/types stay bound to the contract's
 * ProcessingTaskStep (a renamed or retyped field breaks compile here), but
 * beyond the display essentials everything is optional because SSE step
 * fragments and placeholder rows are constructed client-side.
 */
export interface ProcessingTaskStepDTO {
  stepName: ContractStep["stepName"] | (string & {});
  status: ContractStep["status"] | (string & {});
  progress: ContractStep["progress"];
  retryable?: ContractStep["retryable"];
  source?: ContractStep["source"];
  /** Carried on TaskEvent (not on the step schema); surfaced per-row in the UI. */
  errorCode?: string | null;
}

export interface ProcessingTaskDTO extends Omit<ContractTask, "steps"> {
  steps?: ProcessingTaskStepDTO[] | null;
}

export interface MeetingAggregateDTO {
  meeting: MeetingSummaryDTO | null;
  latestTask: ProcessingTaskDTO | null;
  speakers: MeetingSpeakerListDTO | null;
  minutes: { title?: string; markdown: string; minutesVersion?: number; meetingId?: string } | null;
  /** Sub-resources whose upstream fetch FAILED (≠ "not produced yet"). */
  degraded?: string[];
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

type ContractTaskEvent = components["schemas"]["TaskEvent"];

/**
 * Contract-shaped SSE task event: eventId / sequenceNo / eventType / status /
 * emittedAt are required, exactly as Java emits them. Only `steps` is relaxed
 * to the UI-lenient step row above.
 */
export interface TaskEventDTO extends Omit<ContractTaskEvent, "steps"> {
  steps?: ProcessingTaskStepDTO[] | null;
}
