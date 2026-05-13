import { describe, it, expect } from "vitest";
import { sseReducer, createInitialSnapshot, type TaskSnapshot } from "../sse-reducer";
import type { TaskEvent, ProcessingTaskStep } from "@shared/api/types";

function makeStep(name: string, status: string, progress = 0): ProcessingTaskStep {
  return {
    stepName: name as ProcessingTaskStep["stepName"],
    status: status as ProcessingTaskStep["status"],
    progress,
    source: "AI_WORKER_CALLBACK" as ProcessingTaskStep["source"],
    startedAt: "2026-01-01T00:00:00Z",
  };
}

function baseEvent(eventType: TaskEvent["eventType"], taskId: string): TaskEvent {
  return {
    eventId: `evt_${taskId}_${eventType}`,
    sequenceNo: 1,
    eventType,
    taskId,
    status: "RUNNING",
    emittedAt: "2026-01-01T00:00:00Z",
  };
}

describe("sseReducer", () => {
  const baseState: TaskSnapshot = {
    ...createInitialSnapshot(),
    taskId: "task_01",
    meetingId: "mtg_01",
    status: "RUNNING",
    phase: "WORKER_DAG_RUNNING",
    attemptNo: 1,
    currentStep: "ASR",
    steps: [makeStep("ASR", "RUNNING", 50)],
    completedSteps: [],
  };

  it("TASK_SNAPSHOT replaces the full state", () => {
    const event = {
      ...baseEvent("TASK_SNAPSHOT", "task_01"),
      meetingId: "mtg_01",
      status: "SUCCEEDED",
      phase: "TERMINAL",
      attemptNo: 1,
      stepName: null,
      errorCode: null,
      retryable: false,
      steps: [makeStep("ASR", "SUCCEEDED", 100)],
      completedSteps: ["ASR"],
    } satisfies TaskEvent;
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("SUCCEEDED");
    expect(next.phase).toBe("TERMINAL");
    expect(next.currentStep).toBeNull();
    expect(next.completedSteps).toEqual(["ASR"]);
  });

  it("TASK_STEP_UPDATED mutates the matching step", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_STEP_UPDATED", "task_01"),
      status: "RUNNING",
      stepName: "ASR",
      progress: 75,
      completedSteps: [],
    };
    const next = sseReducer(baseState, event);
    expect(next.steps[0]!.progress).toBe(75);
    expect(next.steps[0]!.status).toBe("RUNNING");
  });

  it("TASK_HEARTBEAT updates leaseExpiresAt", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_HEARTBEAT", "task_01"),
      progress: 80,
      leaseExpiresAt: "2026-01-01T00:02:00Z",
    };
    const next = sseReducer(baseState, event);
    expect(next.leaseExpiresAt).toBe("2026-01-01T00:02:00Z");
  });

  it("TASK_FAILED sets status to FAILED and records errorCode", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_FAILED", "task_01"),
      errorCode: "GPU_OOM",
      retryable: true,
    };
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("FAILED");
    expect(next.lastErrorCode).toBe("GPU_OOM");
    expect(next.retryable).toBe(true);
  });

  it("TASK_COMPLETED sets status to SUCCEEDED and phase to TERMINAL", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_COMPLETED", "task_01"),
      status: "SUCCEEDED",
    };
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("SUCCEEDED");
    expect(next.phase).toBe("TERMINAL");
  });

  it("TASK_CANCELLED sets status to CANCELLED", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_CANCELLED", "task_01"),
      status: "CANCELLED",
    };
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("CANCELLED");
  });

  it("TRANSCRIPT_READY records transcriptVersion", () => {
    const event: TaskEvent = {
      ...baseEvent("TRANSCRIPT_READY", "task_01"),
      transcriptVersion: 3,
    };
    const next = sseReducer(baseState, event);
    expect(next.transcriptVersion).toBe(3);
  });

  it("unknown event type returns the same state", () => {
    const event = { ...baseEvent("TASK_SNAPSHOT" as TaskEvent["eventType"], "task_01") };
    (event as unknown as Record<string, string>).eventType = "UNKNOWN_EVENT";
    const next = sseReducer(baseState, event as TaskEvent);
    expect(next).toEqual(baseState);
  });
});
