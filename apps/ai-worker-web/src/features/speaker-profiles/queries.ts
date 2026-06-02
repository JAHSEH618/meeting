import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listSpeakerProfiles, revokeSpeakerProfile } from "@/shared/api/endpoints";
import type { SpeakerProfileDTO } from "@/shared/api/types";

export function useSpeakerProfilesQuery() {
  return useQuery<SpeakerProfileDTO[]>({
    queryKey: ["admin", "speaker-profiles"],
    queryFn: () => listSpeakerProfiles(),
  });
}

export function useRevokeSpeakerProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ profileId, reason }: { profileId: string; reason?: string }) =>
      revokeSpeakerProfile(profileId, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "speaker-profiles"] }),
  });
}
