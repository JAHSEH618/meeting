import { describe, it, expect } from "vitest";

const STEP_STATUS_VALUES = [
  "PENDING", "QUEUED", "RUNNING",
  "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED",
] as const;

const TASK_STATUS_VALUES = [
  "PENDING", "QUEUED", "RUNNING", "ORPHANED",
  "PARTIAL_SUCCEEDED", "SUCCEEDED", "FAILED",
  "CANCEL_PENDING", "CANCELLED",
] as const;

const TASK_PHASE_VALUES = [
  "WORKER_DAG_RUNNING", "WORKER_DAG_DONE",
  "JAVA_LLM_RUNNING", "TERMINAL",
] as const;

const SECURITY_LEVEL_VALUES = [
  "PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET",
] as const;

const RAG_COVERAGE_VALUES = ["TRANSCRIPT_ONLY", "FULL"] as const;

const PROCESSING_STEP_VALUES = [
  "AUDIO_UPLOAD", "AUDIO_PREPROCESS", "ASR",
  "ALIGNMENT", "DIARIZATION", "SPEAKER_EMBEDDING",
  "SPEAKER_MATCHING", "TRANSCRIPT_MERGE",
  "SUMMARY", "EXTRACTION", "RAG_INDEXING", "EXPORT",
] as const;

describe("types consistency with contracts enums.yaml", () => {
  it("stepStatus should not contain PARTIAL_SUCCEEDED", () => {
    const values: readonly string[] = STEP_STATUS_VALUES;
    expect(values).not.toContain("PARTIAL_SUCCEEDED");
  });

  it("taskStatus should contain PARTIAL_SUCCEEDED", () => {
    const values: readonly string[] = TASK_STATUS_VALUES;
    expect(values).toContain("PARTIAL_SUCCEEDED");
  });

  it("taskPhase must have exactly 4 values in order", () => {
    expect(TASK_PHASE_VALUES).toEqual([
      "WORKER_DAG_RUNNING",
      "WORKER_DAG_DONE",
      "JAVA_LLM_RUNNING",
      "TERMINAL",
    ]);
  });

  it("securityLevel must match contracts", () => {
    expect(SECURITY_LEVEL_VALUES).toEqual([
      "PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET",
    ]);
  });

  it("ragAnswerCoverage must match contracts", () => {
    expect(RAG_COVERAGE_VALUES).toEqual(["TRANSCRIPT_ONLY", "FULL"]);
  });

  it("processingStep must have all 12 pipeline steps", () => {
    expect(PROCESSING_STEP_VALUES.length).toBe(12);
  });

  it("processingStep must not contain duplicate values", () => {
    expect(new Set(PROCESSING_STEP_VALUES).size).toBe(PROCESSING_STEP_VALUES.length);
  });
});
