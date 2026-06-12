import { describe, it, expect, beforeAll } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";
import { load } from "js-yaml";

const CONTRACTS_DIR = resolve(__dirname, "../../../../../../packages/meeting-contracts");

let enumsYaml: Record<string, string[]> | undefined;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let publicApiYaml: any;

beforeAll(() => {
  enumsYaml = load(
    readFileSync(resolve(CONTRACTS_DIR, "schemas/common/enums.yaml"), "utf-8")
  ) as Record<string, string[]>;
  publicApiYaml = load(
    readFileSync(resolve(CONTRACTS_DIR, "openapi/public-api.yaml"), "utf-8")
  );
});

function enumFromYaml(name: string): string[] {
  return (enumsYaml as Record<string, string[]>)[name] ?? [];
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function getSchema(name: string): any {
  return publicApiYaml?.components?.schemas?.[name] ?? {};
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

  it("securityLevel must be removed from contracts (Phase K)", () => {
    expect(enumsYaml).not.toHaveProperty("securityLevel");
    expect(publicApiYaml?.components?.schemas?.SecurityLevel).toBeUndefined();
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

  it("audioUploadStatus must match contracts", () => {
    const expected = enumFromYaml("audioUploadStatus");
    expect(["INITIATED", "UPLOADING", "COMPLETED", "ABORTED", "EXPIRED"]).toEqual(expected);
  });

  it("taskEventType must match contracts", () => {
    const expected = enumFromYaml("taskEventType");
    expect(["TASK_SNAPSHOT", "TASK_STARTED", "TASK_STEP_UPDATED", "TASK_HEARTBEAT", "TRANSCRIPT_READY", "TASK_FAILED", "TASK_COMPLETED", "TASK_CANCELLED", "EXPORT_STATUS_CHANGED"]).toEqual(expected);
  });

  it("sourceType must match contracts", () => {
    const expected = enumFromYaml("sourceType");
    expect(["PRIMARY_TRANSCRIPT", "AI_SUMMARY", "MINUTES", "DECISION", "ACTION_ITEM", "RISK", "DOCUMENT"]).toEqual(expected);
  });
});

describe("DTO shape consistency with public-api.yaml", () => {
  it("ApiResponse must have required envelope fields", () => {
    const schema = getSchema("ApiResponse");
    const required = (schema.required as string[]) ?? [];
    expect(new Set(required)).toEqual(new Set(["success", "data", "error", "requestId", "traceId"]));
    expect(schema.properties.success?.type).toBe("boolean");
    expect(schema.properties.requestId?.type).toBe("string");
    expect(schema.properties.traceId?.type).toBe("string");
    expect(schema.properties.error).toBeDefined();
  });

  it("ErrorInfo must have code, message, retryable", () => {
    const schema = getSchema("ErrorInfo");
    const required = (schema.required as string[]) ?? [];
    expect(new Set(required)).toEqual(new Set(["code", "message", "retryable"]));
    expect(schema.properties.code?.type).toBe("string");
    expect(schema.properties.message?.type).toBe("string");
    expect(schema.properties.retryable?.type).toBe("boolean");
  });

  it("PageInfo must have cursor, hasMore, limit", () => {
    const schema = getSchema("PageInfo");
    expect(schema.properties.cursor?.type).toBe("string");
    expect(schema.properties.hasMore?.type).toBe("boolean");
    expect(schema.properties.limit?.type).toBe("integer");
  });

  it("Meeting must have correct property types", () => {
    const schema = getSchema("Meeting");
    expect(schema.properties.meetingId).toBeDefined();
    expect(schema.properties.tenantId).toBeDefined();
    expect(schema.properties.title?.type).toBe("string");
    expect(schema.properties.securityLevel).toBeUndefined();
    expect(schema.properties.status?.$ref).toBe("#/components/schemas/MeetingStatus");
    expect(schema.properties.transcriptVersion?.type).toBe("integer");
    expect(schema.properties.minutesVersion?.type).toBe("integer");
    expect(schema.properties.createdAt).toBeDefined();
  });

  it("ProcessingTask must reference ProcessingTaskPhase", () => {
    const schema = getSchema("ProcessingTask");
    expect(schema.properties.taskId).toBeDefined();
    expect(schema.properties.status).toBeDefined();
    expect(schema.properties.phase?.$ref).toBe("#/components/schemas/ProcessingTaskPhase");
    expect(schema.properties.steps).toBeDefined();
    expect(schema.properties.attemptNo?.type).toBe("integer");
    expect(schema.properties.retryable?.type).toBe("boolean");
  });

  it("ProcessingTaskStep must reference ProcessingStepUpdateSource", () => {
    const schema = getSchema("ProcessingTaskStep");
    expect(schema.properties.stepName).toBeDefined();
    expect(schema.properties.status).toBeDefined();
    expect(schema.properties.source?.$ref).toBe("#/components/schemas/ProcessingStepUpdateSource");
    expect(schema.properties.progress?.type).toBe("integer");
  });

  it("AudioUploadSession must expose phase 2 upload fields", () => {
    const schema = getSchema("AudioUploadSession");
    const required = (schema.required as string[]) ?? [];
    expect(required).toContain("uploadId");
    expect(required).toContain("meetingId");
    expect(required).toContain("uploadStatus");
    expect(required).toContain("partSizeBytes");
    expect(required).toContain("maxPartCount");
    expect(required).toContain("fileSha256");
    expect(required).toContain("parts");
    expect(schema.properties.uploadStatus?.$ref).toBe("#/components/schemas/AudioUploadStatus");
    expect(schema.properties.partSizeBytes?.default).toBe(8388608);
    expect(schema.properties.maxPartCount?.default).toBe(10000);
  });

  it("MeetingSegmentCitation must have correct property types", () => {
    const schema = getSchema("MeetingSegmentCitation");
    const required = (schema.required as string[]) ?? [];
    expect(required).toContain("type");
    expect(required).toContain("meetingId");
    expect(required).toContain("segmentId");
    expect(required).toContain("startMs");
    expect(required).toContain("endMs");
    expect(schema.properties.startMs?.type).toBe("integer");
    expect(schema.properties.endMs?.type).toBe("integer");
    expect(schema.properties.type?.type).toBe("string");
  });

  it("TranscriptSegment must have required fields", () => {
    const schema = getSchema("TranscriptSegment");
    const required = (schema.required as string[]) ?? [];
    expect(required).toContain("segmentId");
    expect(required).toContain("startMs");
    expect(required).toContain("endMs");
    expect(required).toContain("speakerLabel");
    expect(required).toContain("originalText");
    expect(required).toContain("asrConfidence");
    expect(schema.properties.timestampPrecision).toBeDefined();
  });

  it("RagAnswerDTO must have correct property types", () => {
    const schema = getSchema("RagAnswerDTO");
    const required = (schema.required as string[]) ?? [];
    expect(new Set(required)).toEqual(new Set(["answer", "citations", "coverage", "artifactManifestId"]));
    expect(schema.properties.answer?.type).toBe("string");
    expect(schema.properties.citations).toBeDefined();
    expect(schema.properties.coverage?.$ref).toBe("#/components/schemas/RagAnswerCoverage");
    expect(schema.properties.artifactManifestId?.type).toBe("string");
  });

  it("AuthUser must have required fields", () => {
    const schema = getSchema("AuthUser");
    const required = (schema.required as string[]) ?? [];
    expect(new Set(required)).toEqual(
      new Set(["userId", "tenantId", "displayName", "roles", "permissions"])
    );
  });

  it("All response schemas must use ApiResponse envelope pattern", () => {
    const schemas: Record<string, Record<string, Record<string, unknown>>> = publicApiYaml?.components?.schemas ?? {};
    for (const [name, schema] of Object.entries(schemas)) {
      if (name.endsWith("Response") && name !== "ApiResponse") {
        expect(schema.properties?.success).toBeDefined();
        expect(schema.properties?.data).toBeDefined();
        expect(schema.properties?.error).toBeDefined();
        expect(schema.properties?.requestId).toBeDefined();
        expect(schema.properties?.traceId).toBeDefined();
      }
    }
  });
});
