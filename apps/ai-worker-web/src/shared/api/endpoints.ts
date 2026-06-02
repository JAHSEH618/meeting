import type {
  AudioUploadSessionDTO,
  CreateDocumentRequest,
  CreatePersonRequest,
  DocumentSummaryDTO,
  EnrollmentSessionDTO,
  ExportJobDTO,
  FileUploadCompleteResponseDTO,
  FileUploadPartDTO,
  FileUploadSessionDTO,
  GlossaryTermDTO,
  MeetingAggregateDTO,
  MeetingDocumentItemDTO,
  MeetingSummaryDTO,
  MeetingSpeakerDTO,
  PersonDTO,
  ProcessingTaskDTO,
  SecurityLevel,
  VoiceprintDTO,
} from "@/shared/api/types";
import { apiCall, apiUpload } from "@/shared/api/client";

const API = "/admin";

/** Persons search (public API passthrough). */
export async function searchPersons(q?: string, opts: { signal?: AbortSignal } = {}) {
  const persons = await apiCall<PersonDTO[]>(`${API}/persons`, { query: { q }, signal: opts.signal });
  return persons.map(normalizePerson);
}

export async function createPerson(req: CreatePersonRequest): Promise<PersonDTO> {
  const person = await apiCall<PersonDTO>(`${API}/persons`, { method: "POST", body: req });
  return normalizePerson(person);
}

/** Voiceprint enrollment 4-step ritual. */
export const createEnrollmentSession = (personId: string | null) =>
  apiCall<EnrollmentSessionDTO>(`${API}/enrollment/sessions`, { method: "POST", body: { personId } });

export const uploadEnrollmentAudio = (sessionId: string, audio: Blob) =>
  apiUpload(`${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/audio`, audio);

export const previewEnrollment = (sessionId: string) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/preview`,
    { method: "POST" },
  );

export const commitEnrollment = (sessionId: string) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/commit`,
    { method: "POST" },
  );

export const listVoiceprints = (personId?: string) =>
  apiCall<VoiceprintDTO[]>(`${API}/voiceprints`, { query: { personId } });

export const revokeVoiceprint = (enrollmentId: string) =>
  apiCall<void>(`${API}/voiceprints/${encodeURIComponent(enrollmentId)}:revoke`, { method: "POST" });

/** Meetings + workstation pages. */
export const createMeeting = (body: {
  title: string;
  securityLevel: string;
  language: string;
  participants: Array<{ personId: string; displayName: string; role: string }>;
}) => apiCall<MeetingSummaryDTO>(`${API}/meetings`, { method: "POST", body });

export async function listAdminMeetings() {
  const data = await apiCall<MeetingSummaryDTO[] | { items?: MeetingSummaryDTO[] }>(`${API}/meetings`);
  return Array.isArray(data) ? data : data.items ?? [];
}

export const getMeetingAggregate = (meetingId: string) =>
  apiCall<MeetingAggregateDTO>(`${API}/meetings/${encodeURIComponent(meetingId)}`);

export const searchDocuments = (q?: string, opts: { signal?: AbortSignal } = {}) =>
  apiCall<DocumentSummaryDTO[]>(`${API}/documents`, { query: { q }, signal: opts.signal });

export const createDocument = (req: CreateDocumentRequest) =>
  apiCall<DocumentSummaryDTO>(`${API}/documents`, { method: "POST", body: req });

export const attachMeetingDocument = (meetingId: string, body: { documentId: string; role: "REFERENCE" | "ATTACHMENT" }) =>
  apiCall<MeetingDocumentItemDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/documents:attach`,
    { method: "POST", body },
  );

export const updateMeetingGlossary = (meetingId: string, terms: GlossaryTermDTO[]) =>
  apiCall<{ meetingId: string; terms: GlossaryTermDTO[] }>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/glossary`,
    { method: "PATCH", body: { terms } },
  );

export const confirmSpeaker = (
  meetingId: string,
  label: string,
  body: { personId: string; speakerProfileId: string; expectedTranscriptVersion: number },
) =>
  apiCall<MeetingSpeakerDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/speakers/${encodeURIComponent(label)}:confirm`,
    { method: "POST", body },
  );

export const createExport = (meetingId: string, format: "DOCX" | "PDF" | "MARKDOWN" = "DOCX") =>
  apiCall<ExportJobDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/exports`,
    { method: "POST", body: { format } },
  );

export const pollExport = (meetingId: string, exportId: string) =>
  apiCall<ExportJobDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/exports/${encodeURIComponent(exportId)}`,
  );

export const getProcessingTask = (taskId: string) =>
  apiCall<ProcessingTaskDTO>(`/api/processing-tasks/${encodeURIComponent(taskId)}`);

export const processingTaskEventsUrl = (taskId: string) =>
  `/api/processing-tasks/${encodeURIComponent(taskId)}/events`;

export const initFileUpload = (req: {
  fileName: string;
  contentType: string;
  fileSizeBytes: number;
  fileSha256: string;
  partSizeBytes?: number;
}) => apiCall<FileUploadSessionDTO>(`${API}/files/uploads`, { method: "POST", body: req });

export const createFileUploadPart = (
  uploadId: string,
  req: { partNumber: number; sizeBytes: number; partSha256: string },
) =>
  apiCall<FileUploadPartDTO>(
    `${API}/files/uploads/${encodeURIComponent(uploadId)}/parts`,
    { method: "POST", body: req },
  );

export const completeFileUpload = (
  uploadId: string,
  req: { fileSha256: string; parts: { partNumber: number; partSha256: string; etag: string }[] },
) =>
  apiCall<FileUploadCompleteResponseDTO>(
    `${API}/files/uploads/${encodeURIComponent(uploadId)}/complete`,
    { method: "POST", body: req },
  );

export const abortFileUpload = (uploadId: string) =>
  apiCall<void>(`${API}/files/uploads/${encodeURIComponent(uploadId)}/abort`, { method: "POST" });

export const initAudioUpload = (
  meetingId: string,
  req: {
    fileName: string;
    contentType: string;
    fileSizeBytes: number;
    fileSha256: string;
    partSizeBytes?: number;
  },
) =>
  apiCall<AudioUploadSessionDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/files/audio/uploads`,
    { method: "POST", body: req },
  );

export const createAudioUploadPart = (
  meetingId: string,
  uploadId: string,
  req: { partNumber: number; sizeBytes: number; partSha256: string },
) =>
  apiCall<FileUploadPartDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/files/audio/uploads/${encodeURIComponent(uploadId)}/parts`,
    { method: "POST", body: req },
  );

export const completeAudioUpload = (
  meetingId: string,
  uploadId: string,
  req: {
    fileSha256: string;
    durationMs?: number;
    parts: { partNumber: number; partSha256: string; etag: string }[];
  },
) =>
  apiCall<AudioUploadSessionDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/files/audio/uploads/${encodeURIComponent(uploadId)}/complete`,
    { method: "POST", body: req },
  );

export const abortAudioUpload = (meetingId: string, uploadId: string) =>
  apiCall<void>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/files/audio/uploads/${encodeURIComponent(uploadId)}/abort`,
    { method: "POST" },
  );

export type { SecurityLevel };

function normalizePerson(person: PersonDTO): PersonDTO {
  const personId = person.personId ?? person.id ?? "";
  return {
    ...person,
    personId,
    email: person.email ?? null,
    externalId: person.externalId ?? null,
    createdAt: person.createdAt ?? "",
  };
}
