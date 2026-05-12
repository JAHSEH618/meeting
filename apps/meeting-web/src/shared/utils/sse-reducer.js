const emptySnapshot = {
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
export function sseReducer(state, event) {
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
                leaseExpiresAt: event.leaseExpiresAt ?? state.leaseExpiresAt,
            };
        case "TASK_STEP_UPDATED":
            return {
                ...state,
                status: event.status,
                currentStep: event.stepName ?? state.currentStep,
                steps: state.steps.map((s) => s.stepName === event.stepName
                    ? { ...s, status: event.status, progress: event.progress ?? s.progress }
                    : s),
                completedSteps: event.completedSteps ?? state.completedSteps,
            };
        case "TASK_HEARTBEAT":
            return {
                ...state,
                progress: event.progress ?? undefined,
                leaseExpiresAt: event.leaseExpiresAt ?? state.leaseExpiresAt,
            };
        case "TRANSCRIPT_READY":
            return {
                ...state,
                transcriptVersion: event.transcriptVersion
                    ? event.transcriptVersion
                    : undefined,
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
                phase: "TERMINAL",
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
export function createInitialSnapshot() {
    return { ...emptySnapshot };
}
