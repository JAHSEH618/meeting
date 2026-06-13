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

export function useAdminMeetingsQuery() {
  return useQuery<MeetingSummaryDTO[]>({
    queryKey: ["admin", "meetings"],
    queryFn: () => listAdminMeetings(),
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
