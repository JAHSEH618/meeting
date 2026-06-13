import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createMeeting as apiCreateMeeting,
  createProcessingTask,
  getMeeting,
  listMeetings,
} from "@shared/api/client";
import { generateStableIdempotencyKey } from "@shared/utils/idempotency";
import { useAuthStore } from "@shared/stores/auth";
import type { Meeting, CreateMeetingRequest } from "@shared/api/types";
import { invalidateAfter } from "@shared/queries/invalidation-matrix";

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
  const userId = useAuthStore((s) => s.user?.userId || 'anonymous');

  return useMutation({
    mutationFn: async (body: CreateMeetingRequest) => {
      const idempotencyKey = generateStableIdempotencyKey('create-meeting', userId);
      return apiCreateMeeting(body, idempotencyKey);
    },
    onSuccess: (data) => invalidateAfter({ type: "meeting-created", meetingId: data.meetingId }, qc),
  });
}

export function useStartTask(meetingId: string) {
  const qc = useQueryClient();
  const userId = useAuthStore((s) => s.user?.userId || 'anonymous');

  return useMutation({
    mutationFn: async (audioFileId: string) => {
      const idempotencyKey = generateStableIdempotencyKey('create-task', userId, meetingId);
      return createProcessingTask(meetingId, audioFileId, idempotencyKey);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["meeting", meetingId] });
      qc.invalidateQueries({ queryKey: ["meeting-task", meetingId] });
    },
  });
}
