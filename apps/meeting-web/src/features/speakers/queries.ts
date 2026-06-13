import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  confirmMeetingSpeaker,
  createSpeakerProfile,
  deleteSpeakerProfile,
  listSpeakerEnrollments,
  listSpeakerProfiles,
  rejectMeetingSpeaker,
  revokeSpeakerProfile,
} from "@shared/api/client";
import type { SpeakerEnrollment, SpeakerProfile } from "@shared/api/client";
import { invalidateAfter } from "@shared/queries/invalidation-matrix";

export function useSpeakerProfilesQuery() {
  return useQuery<{ items: SpeakerProfile[] }>({
    queryKey: ["speaker-profiles"],
    queryFn: () => listSpeakerProfiles(),
  });
}

export function useSpeakerEnrollmentsQuery(profileId: string, enabled: boolean) {
  return useQuery<{ items: SpeakerEnrollment[] }>({
    queryKey: ["speaker-enrollments", profileId],
    queryFn: () => listSpeakerEnrollments(profileId),
    enabled: enabled && !!profileId,
    refetchInterval: false,
  });
}

export function useCreateSpeakerProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createSpeakerProfile,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["speaker-profiles"] }),
  });
}

export function useRevokeSpeakerProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (profileId: string) => revokeSpeakerProfile(profileId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["speaker-profiles"] }),
  });
}

export function useDeleteSpeakerProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (profileId: string) => deleteSpeakerProfile(profileId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["speaker-profiles"] }),
  });
}

export function useConfirmMeetingSpeaker(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      speakerLabel: string;
      personId: string;
      speakerProfileId: string;
      expectedTranscriptVersion: number;
    }) =>
      confirmMeetingSpeaker(meetingId, input.speakerLabel, {
        personId: input.personId,
        speakerProfileId: input.speakerProfileId,
        expectedTranscriptVersion: input.expectedTranscriptVersion,
      }),
    onSuccess: () => invalidateAfter({ type: "speaker-confirmed", meetingId }, qc),
  });
}

export function useRejectMeetingSpeaker(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (speakerLabel: string) => rejectMeetingSpeaker(meetingId, speakerLabel),
    onSuccess: () => invalidateAfter({ type: "speaker-confirmed", meetingId }, qc),
  });
}
