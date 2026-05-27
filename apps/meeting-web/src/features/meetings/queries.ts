import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createMeeting as apiCreateMeeting,
  createProcessingTask,
  getMeeting,
  listMeetings,
} from "@shared/api/client";
import type { Meeting } from "@shared/api/types";

export function useMeetingsQuery() {
  return useQuery<{ items: Meeting[]; total?: number }>({
    queryKey: ["meetings"],
    queryFn: () => listMeetings(),
  });
}

export function useMeetingQuery(meetingId: string | undefined) {
  return useQuery<Meeting>({
    queryKey: ["meeting", meetingId],
    queryFn: () => getMeeting(meetingId!),
    enabled: !!meetingId,
  });
}

export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: apiCreateMeeting,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["meetings"] }),
  });
}

export function useStartTask(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (audioFileId: string) => createProcessingTask(meetingId, audioFileId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["meeting", meetingId] });
      qc.invalidateQueries({ queryKey: ["meeting-task", meetingId] });
    },
  });
}
