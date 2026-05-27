import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMeeting, getMinutes, regenerateMinutes } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";

export function useMinutesQuery(meetingId: string) {
  return useQuery({
    queryKey: ["minutes", meetingId],
    queryFn: () => getMinutes(meetingId),
    enabled: !!meetingId,
    retry: (count, err) => {
      const apiErr = err as ApiClientError;
      return apiErr?.status !== 404 && apiErr?.code !== "SECURITY_LEVEL_BLOCKED" && count < 2;
    },
  });
}

export function useMeetingForMinutes(meetingId: string) {
  return useQuery({
    queryKey: ["meeting", meetingId],
    queryFn: () => getMeeting(meetingId),
    enabled: !!meetingId,
  });
}

export function useRegenerateMinutes(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { transcriptVersion: number; minutesVersion: number }) =>
      regenerateMinutes(meetingId, input.transcriptVersion, input.minutesVersion),
    onSuccess: (data) => {
      qc.setQueryData(["minutes", meetingId], data);
    },
  });
}
