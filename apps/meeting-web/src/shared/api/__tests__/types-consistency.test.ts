import { describe, it, expect, beforeAll } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";
import { load } from "js-yaml";

const CONTRACTS_DIR = resolve(__dirname, "../../../../../../packages/meeting-contracts");

let enumsYaml: Record<string, string[]> | undefined;

beforeAll(() => {
  const raw = readFileSync(resolve(CONTRACTS_DIR, "schemas/common/enums.yaml"), "utf-8");
  enumsYaml = load(raw) as Record<string, string[]>;
});

function enumFromYaml(name: string): string[] {
  return (enumsYaml as Record<string, string[]>)[name] ?? [];
}

describe("types consistency with contracts enums.yaml", () => {
  it("stepStatus should match contracts", () => {
    const expected = enumFromYaml("stepStatus");
    expect(["PENDING", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"]).toEqual(expected);
  });

  it("taskStatus should match contracts", () => {
    const expected = enumFromYaml("processingTaskStatus");
    expect(["PENDING", "QUEUED", "RUNNING", "ORPHANED", "PARTIAL_SUCCEEDED", "SUCCEEDED", "FAILED", "CANCEL_PENDING", "CANCELLED"]).toEqual(expected);
  });

  it("taskPhase must match contracts", () => {
    const expected = enumFromYaml("processingTaskPhase");
    expect(["WORKER_DAG_RUNNING", "WORKER_DAG_DONE", "JAVA_LLM_RUNNING", "TERMINAL"]).toEqual(expected);
  });

  it("securityLevel must match contracts", () => {
    const expected = enumFromYaml("securityLevel");
    expect(["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"]).toEqual(expected);
  });

  it("ragAnswerCoverage must match contracts", () => {
    const expected = enumFromYaml("ragAnswerCoverage");
    expect(["TRANSCRIPT_ONLY", "FULL"]).toEqual(expected);
  });

  it("processingStep must match contracts", () => {
    const expected = enumFromYaml("processingStep");
    expect(["AUDIO_UPLOAD", "AUDIO_PREPROCESS", "ASR", "ALIGNMENT", "DIARIZATION", "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE", "SUMMARY", "EXTRACTION", "RAG_INDEXING", "EXPORT"]).toEqual(expected);
  });

  it("processingStepUpdateSource must match contracts", () => {
    const expected = enumFromYaml("processingStepUpdateSource");
    expect(["JAVA_TASK_SERVICE", "AI_WORKER_CALLBACK"]).toEqual(expected);
  });

  it("taskEventType must match contracts", () => {
    const expected = enumFromYaml("taskEventType");
    expect(["TASK_SNAPSHOT", "TASK_STARTED", "TASK_STEP_UPDATED", "TASK_HEARTBEAT", "TRANSCRIPT_READY", "TASK_FAILED", "TASK_COMPLETED", "TASK_CANCELLED"]).toEqual(expected);
  });

  it("sourceType must match contracts", () => {
    const expected = enumFromYaml("sourceType");
    expect(["PRIMARY_TRANSCRIPT", "AI_SUMMARY", "DECISION", "ACTION_ITEM", "RISK", "DOCUMENT"]).toEqual(expected);
  });
});