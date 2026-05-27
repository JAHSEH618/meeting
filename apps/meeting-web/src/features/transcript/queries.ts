import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLatestMeetingTask, getTranscript, updateSegment } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";

export function useTranscriptQuery(meetingId: string) {
  return useQuery({
    queryKey: ["transcript", meetingId],
    queryFn: () => getTranscript(meetingId),
    enabled: !!meetingId,
    retry: (count, err) => (err as ApiClientError)?.status !== 404 && count < 2,
  });
}

export function useLatestMeetingTaskQuery(meetingId: string) {
  return useQuery({
    queryKey: ["meeting-task", meetingId],
    queryFn: () => getLatestMeetingTask(meetingId),
    enabled: !!meetingId,
    retry: (count, err) => (err as ApiClientError)?.status !== 404 && count < 2,
  });
}

export function useUpdateSegment(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { segmentId: string; text: string; version: number; reason: string | null }) =>
      updateSegment(meetingId, input.segmentId, input.text, input.version, input.reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transcript", meetingId] });
      qc.invalidateQueries({ queryKey: ["minutes", meetingId] });
    },
  });
}
