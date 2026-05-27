import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createSpeakerProfile,
  deleteSpeakerProfile,
  listSpeakerEnrollments,
  listSpeakerProfiles,
  revokeSpeakerProfile,
} from "@shared/api/client";
import type { SpeakerEnrollment, SpeakerProfile } from "@shared/api/client";

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
