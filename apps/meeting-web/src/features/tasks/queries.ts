import { useMutation, useQueryClient } from "@tanstack/react-query";
import { cancelTask, retryTask } from "@shared/api/client";

export function useRetryTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => retryTask(taskId),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => cancelTask(taskId),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}
