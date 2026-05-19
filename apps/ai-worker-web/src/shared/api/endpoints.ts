import type {
  DocumentSummaryDTO,
  EnrollmentSessionDTO,
  ExportJobDTO,
  GlossaryTermDTO,
  MeetingAggregateDTO,
  MeetingDocumentItemDTO,
  MeetingSummaryDTO,
  MeetingSpeakerDTO,
  PersonDTO,
  ProcessingTaskDTO,
  VoiceprintDTO,
} from "@/shared/api/types";
import { apiCall, apiUpload } from "@/shared/api/client";

const API = "/admin";

/** Persons search (public API passthrough). */
export const searchPersons = (q?: string) =>
  apiCall<PersonDTO[]>(`${API}/persons`, { query: { q } });

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

/** Meetings + workstation wizard. */
export const createMeeting = (body: {
  title: string;
  securityLevel: string;
  language: string;
  participants: Array<{ personId: string; displayName: string; role: string }>;
}) => apiCall<MeetingSummaryDTO>(`${API}/meetings`, { method: "POST", body });

export const getMeetingAggregate = (meetingId: string) =>
  apiCall<MeetingAggregateDTO>(`${API}/meetings/${encodeURIComponent(meetingId)}`);

export const searchDocuments = (q?: string) =>
  apiCall<DocumentSummaryDTO[]>(`${API}/documents`, { query: { q } });

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

export const startMeetingProcessing = (meetingId: string) =>
  apiCall<ProcessingTaskDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}:start-processing`,
    { method: "POST", body: {} },
  );

export const confirmSpeaker = (meetingId: string, label: string, personId: string) =>
  apiCall<MeetingSpeakerDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/speakers/${encodeURIComponent(label)}:confirm`,
    { method: "POST", body: { personId } },
  );

export const finalizeMeeting = (meetingId: string) =>
  apiCall<ProcessingTaskDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}:finalize`,
    { method: "POST" },
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
