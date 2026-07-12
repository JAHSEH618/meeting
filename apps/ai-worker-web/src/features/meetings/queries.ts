import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  confirmSpeaker,
  createEnrollmentSession,
  commitEnrollment,
  getMeetingAggregate,
  previewEnrollment,
  rejectSpeaker,
  searchPersons,
  updateMeeting,
  uploadEnrollmentAudio,
  listAdminMeetings,
} from "@/shared/api/endpoints";
import type {
  MeetingAggregateDTO,
  MeetingParticipantDTO,
  MeetingSummaryDTO,
  PersonDTO,
  ProcessingTaskStatus,
} from "@/shared/api/types";

// Meeting statuses that can still change on their own (pipeline running).
const LIVE_MEETING_STATUSES = new Set(["CREATED", "PROCESSING", "UPLOADING", "QUEUED", "RUNNING"]);

/** Task statuses that never change again — stop all live updates on these. */
export const TERMINAL_TASK_STATUSES: ProcessingTaskStatus[] = [
  "SUCCEEDED",
  "PARTIAL_SUCCEEDED",
  "FAILED",
  "CANCELLED",
];

export function useAdminMeetingsQuery() {
  return useQuery<MeetingSummaryDTO[]>({
    queryKey: ["admin", "meetings"],
    queryFn: () => listAdminMeetings(),
    // The workstation landing page is a pipeline monitor: while any meeting
    // is still moving, refresh so status pills / counters advance without a
    // manual reload; go quiet once everything is terminal.
    refetchInterval: (query) => {
      const meetings = query.state.data;
      if (!meetings?.length) return false;
      return meetings.some((m) => LIVE_MEETING_STATUSES.has(m.status)) ? 8000 : false;
    },
  });
}

export const meetingAggregateKey = (meetingId: string) =>
  ["admin", "meetings", meetingId, "aggregate"] as const;

/**
 * BFF aggregate for the meeting detail page. The caller opts into a
 * fallback poll (`fallbackPollMs`) when its SSE stream is down; the
 * interval automatically shuts off once the latest task is terminal, so a
 * finished pipeline costs zero upstream calls (the governance fix that
 * replaced the old poll-every-5s-forever behavior).
 */
export function useMeetingAggregateQuery(
  meetingId: string,
  opts: { fallbackPollMs?: number | false } = {},
) {
  const fallbackPollMs = opts.fallbackPollMs ?? false;
  return useQuery<MeetingAggregateDTO>({
    queryKey: meetingAggregateKey(meetingId),
    queryFn: () => getMeetingAggregate(meetingId),
    enabled: !!meetingId,
    refetchInterval: (query) => {
      if (!fallbackPollMs) return false;
      const latest = query.state.data?.latestTask;
      if (latest && TERMINAL_TASK_STATUSES.includes(latest.status)) return false;
      return fallbackPollMs;
    },
  });
}

export function useConfirmSpeaker(meetingId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ speakerLabel, body }: {
      speakerLabel: string;
      body: { personId: string; speakerProfileId: string; expectedTranscriptVersion: number };
    }) => confirmSpeaker(meetingId, speakerLabel, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meetingAggregateKey(meetingId) }),
  });
}

export function useRejectSpeaker(meetingId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (speakerLabel: string) => rejectSpeaker(meetingId, speakerLabel),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meetingAggregateKey(meetingId) }),
  });
}

export function useUpdateMeetingParticipants(meetingId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { participants: MeetingParticipantDTO[]; expectedVersion: number }) =>
      updateMeeting(meetingId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meetingAggregateKey(meetingId) }),
  });
}

export function useSearchPersonsQuery(q: string | undefined) {
  return useQuery<PersonDTO[]>({
    queryKey: ["persons", q ?? ""],
    queryFn: ({ signal }) => searchPersons(q, { signal }),
    enabled: q !== undefined,
  });
}

export function useCreateEnrollmentSession() {
  return useMutation({ mutationFn: (personId: string | null) => createEnrollmentSession(personId) });
}

export function useUploadEnrollmentAudio() {
  return useMutation({
    mutationFn: ({ sessionId, file }: { sessionId: string; file: File }) =>
      uploadEnrollmentAudio(sessionId, file),
  });
}

export function usePreviewEnrollment() {
  return useMutation({ mutationFn: (sessionId: string) => previewEnrollment(sessionId) });
}

export function useCommitEnrollment() {
  return useMutation({
    mutationFn: ({ sessionId, personId }: { sessionId: string; personId: string | null }) =>
      commitEnrollment(sessionId, personId),
  });
}
