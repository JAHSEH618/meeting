import { useMutation, useQuery } from "@tanstack/react-query";
import {
  createEnrollmentSession,
  commitEnrollment,
  previewEnrollment,
  searchPersons,
  uploadEnrollmentAudio,
  listAdminMeetings,
} from "@/shared/api/endpoints";
import type { MeetingSummaryDTO, PersonDTO } from "@/shared/api/types";

// Meeting statuses that can still change on their own (pipeline running).
const LIVE_MEETING_STATUSES = new Set(["CREATED", "PROCESSING", "UPLOADING", "QUEUED", "RUNNING"]);

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
