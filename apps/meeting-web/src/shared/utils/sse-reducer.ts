import type { TaskEvent, ProcessingTaskStep as TaskStep, ProcessingTaskPhase, ProcessingTaskStatus } from "@shared/api/types";

export interface TaskSnapshot {
  taskId: string;
  meetingId: string;
  status: ProcessingTaskStatus | string;
  phase: ProcessingTaskPhase | null;
  attemptNo: number;
  currentStep: string | null;
  lastErrorCode: string | null;
  retryable: boolean;
  steps: TaskStep[];
  completedSteps: string[];
  progress?: number;
  transcriptVersion?: number;
  artifactManifestId?: string;
  leaseExpiresAt?: string;
}

const emptySnapshot: TaskSnapshot = {
  taskId: "",
  meetingId: "",
  status: "PENDING",
  phase: null,
  attemptNo: 0,
  currentStep: null,
  lastErrorCode: null,
  retryable: false,
  steps: [],
  completedSteps: [],
};

export function sseReducer(state: TaskSnapshot, event: TaskEvent): TaskSnapshot {
  switch (event.eventType) {
    case "TASK_SNAPSHOT":
      return {
        taskId: event.taskId,
        meetingId: event.meetingId ?? state.meetingId,
        status: event.status,
        phase: event.phase ?? null,
        attemptNo: event.attemptNo ?? state.attemptNo,
        currentStep: event.stepName ?? null,
        lastErrorCode: event.errorCode ?? null,
        retryable: event.retryable ?? false,
        steps: event.steps ?? state.steps,
        completedSteps: event.completedSteps ?? state.completedSteps,
        progress: event.progress ?? state.progress,
        transcriptVersion: event.transcriptVersion ?? state.transcriptVersion,
        artifactManifestId: event.artifactManifestId ?? state.artifactManifestId,
        leaseExpiresAt: event.leaseExpiresAt ?? state.leaseExpiresAt,
      };

    case "TASK_STEP_UPDATED":
      return {
        ...state,
        currentStep: event.stepName ?? state.currentStep,
        steps: state.steps.map((s) =>
          s.stepName === event.stepName
            ? { ...s, status: event.status as TaskStep["status"], progress: event.progress ?? s.progress }
            : s
        ),
        completedSteps: event.completedSteps ?? state.completedSteps,
      };

    case "TASK_HEARTBEAT":
      return {
        ...state,
        progress: event.progress ?? state.progress,
        leaseExpiresAt: event.leaseExpiresAt ?? state.leaseExpiresAt,
      };

    case "TRANSCRIPT_READY":
      return {
        ...state,
        transcriptVersion: event.transcriptVersion
          ?? state.transcriptVersion,
      };

    case "TASK_FAILED":
      return {
        ...state,
        status: "FAILED",
        lastErrorCode: event.errorCode ?? state.lastErrorCode,
        retryable: event.retryable ?? false,
      };

    case "TASK_COMPLETED":
      return {
        ...state,
        status: "SUCCEEDED",
        phase: "TERMINAL" as ProcessingTaskPhase,
      };

    case "TASK_CANCELLED":
      return {
        ...state,
        status: "CANCELLED",
      };

    default:
      return state;
  }
}

export function createInitialSnapshot(): TaskSnapshot {
  return { ...emptySnapshot };
}
