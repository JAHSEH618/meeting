import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getTask,
  subscribeTaskEvents,
  type TaskEventSubscription,
} from "@shared/api/client";
import {
  createInitialSnapshot,
  sseReducer,
  type TaskSnapshot,
} from "@shared/utils/sse-reducer";
import type {
  ProcessingTask,
  ProcessingTaskStatus,
} from "@shared/api/types";

const TERMINAL: ProcessingTaskStatus[] = [
  "SUCCEEDED",
  "PARTIAL_SUCCEEDED",
  "FAILED",
  "CANCELLED",
];
const POLL_INTERVAL = 3000;

export type ConnectionMode = "SSE" | "POLLING" | "TERMINATED";

function isTerminal(status: ProcessingTaskStatus | string | undefined | null): boolean {
  if (!status) return false;
  return TERMINAL.includes(status as ProcessingTaskStatus);
}

export function snapshotFromTask(task: ProcessingTask): TaskSnapshot {
  return {
    ...createInitialSnapshot(),
    taskId: task.taskId,
    meetingId: task.meetingId ?? "",
    status: task.status,
    phase: task.phase,
    attemptNo: task.attemptNo,
    currentStep: task.currentStep ?? null,
    lastErrorCode: task.lastErrorCode ?? null,
    retryable: task.retryable ?? false,
    steps: task.steps,
    completedSteps: task.steps
      .filter((step) => step.status === "SUCCEEDED")
      .map((step) => step.stepName),
  };
}

export interface UseTaskEventsStreamResult {
  snapshot: TaskSnapshot;
  connectionMode: ConnectionMode;
  isPending: boolean;
  error: unknown;
}

/**
 * Subscribes to a task's SSE stream and bridges events into the
 * react-query cache so consumers can stay declarative.
 *
 * - Initial fetch via REST seeds the snapshot.
 * - SSE updates `setQueryData` so any subscriber to ['task', taskId] sees
 *   the latest snapshot reactively.
 * - On SSE fallback (`onFallback`) the hook flips into polling mode; the
 *   query refetches at `POLL_INTERVAL`.
 * - When the task reaches a terminal status the subscription closes and
 *   the mode reports "TERMINATED".
 */
export function useTaskEventsStream(taskId: string): UseTaskEventsStreamResult {
  const queryClient = useQueryClient();
  const [connectionMode, setConnectionMode] = useState<"SSE" | "POLLING">("SSE");
  const subRef = useRef<TaskEventSubscription | null>(null);
  const lastEventId = useRef<string | null>(null);

  const query = useQuery<TaskSnapshot>({
    queryKey: ["task", taskId],
    queryFn: async () => {
      const task = await getTask(taskId);
      return snapshotFromTask(task);
    },
    enabled: !!taskId,
    refetchInterval: (q) => {
      const status = q.state.data?.status;
      if (isTerminal(status)) return false;
      return connectionMode === "POLLING" ? POLL_INTERVAL : false;
    },
  });

  const status = query.data?.status;
  const terminated = isTerminal(status);

  useEffect(() => {
    if (!taskId) return;
    if (terminated) {
      subRef.current?.close();
      subRef.current = null;
      return;
    }
    subRef.current = subscribeTaskEvents(taskId, {
      lastEventId: lastEventId.current,
      onEvent: (event) => {
        lastEventId.current = event.eventId;
        queryClient.setQueryData<TaskSnapshot>(["task", taskId], (cur) =>
          sseReducer(cur && cur.taskId ? cur : createInitialSnapshot(), event),
        );
      },
      onFallback: () => setConnectionMode("POLLING"),
    });
    return () => {
      subRef.current?.close();
      subRef.current = null;
    };
  }, [taskId, terminated, queryClient]);

  return {
    snapshot: query.data ?? createInitialSnapshot(),
    connectionMode: terminated ? "TERMINATED" : connectionMode,
    isPending: query.isPending,
    error: query.error,
  };
}
