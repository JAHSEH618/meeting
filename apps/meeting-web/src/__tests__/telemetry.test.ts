import { describe, expect, it } from "vitest";
import { redactEvent } from "../services/telemetry";

describe("redactEvent", () => {
  it("keeps allow-listed safe keys", () => {
    const out = redactEvent({
      name: "rag_failed",
      context: {
        route: "/rag",
        errorCode: "RAG_LLM_BLOCKED",
        requestId: "req_01",
        traceId: "trace_01",
        feature: "rag",
        method: "POST",
        status: 422,
        duration: 1.2,
      },
    });
    expect(out.context).toMatchObject({
      route: "/rag",
      errorCode: "RAG_LLM_BLOCKED",
      requestId: "req_01",
      traceId: "trace_01",
      feature: "rag",
      method: "POST",
      status: 422,
      duration: 1.2,
    });
  });

  it.each([
    ["authorization"],
    ["token"],
    ["accessToken"],
    ["refreshToken"],
    ["password"],
    ["secret"],
    ["apiKey"],
    ["hmac"],
    ["transcript"],
    ["text"],
    ["content"],
    ["body"],
    ["payload"],
    ["filename"],
    ["embedding"],
    ["embeddings"],
  ])("strips sensitive key %s", (key) => {
    const out = redactEvent({
      name: "evt",
      context: { [key]: "leaky-value" },
    });
    expect(out.context).toEqual({});
  });

  it("drops unknown context keys", () => {
    const out = redactEvent({
      name: "evt",
      context: {
        meetingTitle: "Q4 strategy review",
        speakerName: "张三",
        evidenceId: "seg_01",
      },
    });
    expect(out.context).toEqual({});
  });

  it("drops nested objects even if the key is allow-listed", () => {
    const out = redactEvent({
      name: "evt",
      context: {
        // route would normally be kept, but the value isn't a scalar
        // so we drop it rather than risk leaking nested data.
        route: { url: "/meetings/mtg_secret/audio" } as unknown as string,
      },
    });
    expect(out.context).toEqual({ route: null });
  });

  it("truncates long string values", () => {
    const out = redactEvent({
      name: "evt",
      context: { errorCode: "X".repeat(300) },
    });
    expect((out.context?.errorCode as string).length).toBe(201);
    expect((out.context?.errorCode as string).endsWith("…")).toBe(true);
  });

  it("summarises Error without stack", () => {
    const err = new Error("ai-worker offline");
    err.name = "AiWorkerUnavailableError";
    const out = redactEvent({ name: "evt", error: err });
    expect(out.error).toEqual({
      message: "ai-worker offline",
      name: "AiWorkerUnavailableError",
    });
  });

  it("summarises string error", () => {
    const out = redactEvent({ name: "evt", error: "boom" });
    expect(out.error).toEqual({ message: "boom" });
  });
});
