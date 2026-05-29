import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { ApiError, apiCall, subscribeEventStream } from "@/shared/api/client";
import { authStore } from "@/shared/auth/store";

describe("apiCall", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    authStore.clear();
    window.__WORKSTATION_CONFIG__ = undefined;
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });
  afterEach(() => {
    authStore.clear();
  });

  it("unwraps the envelope on success", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ success: true, data: { hello: "world" }, error: null, requestId: "r", traceId: "t" }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));
    const data = await apiCall<{ hello: string }>("/admin/ping");
    expect(data).toEqual({ hello: "world" });
  });

  it("adds Authorization header when token is in memory", async () => {
    authStore.set("my-token");
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ success: true, data: null, error: null }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));
    await apiCall("/admin/ping");
    const [, init] = fetchMock.mock.calls[0]!;
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer my-token");
    expect(headers["X-Request-Id"]).toMatch(/[a-f0-9-]{36}/);
  });

  it("adds Idempotency-Key on writes", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ success: true, data: null, error: null }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));
    await apiCall("/admin/ping", { method: "POST", body: { x: 1 }, idempotencyKey: "k_1" });
    const [, init] = fetchMock.mock.calls[0]!;
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("k_1");
    expect((init as RequestInit).body).toBe(JSON.stringify({ x: 1 }));
  });

  it("throws ApiError when envelope.success=false", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({
        success: false,
        data: null,
        error: { code: "VALIDATION_FAILED", message: "bad", retryable: false },
        requestId: "r", traceId: "t",
      }),
      { status: 422, headers: { "Content-Type": "application/json" } },
    ));
    await expect(apiCall("/admin/x")).rejects.toMatchObject({
      status: 422,
      error: { code: "VALIDATION_FAILED" },
    });
  });

  it("clears auth on 401", async () => {
    authStore.set("token");
    window.__WORKSTATION_CONFIG__ = { authLoginUrl: "https://login.example.test/workstation-login" };
    fetchMock.mockResolvedValueOnce(new Response("", { status: 401 }));
    // jsdom locks down window.location.assign; stub by replacing the whole property descriptor.
    const assignSpy = vi.fn();
    const origLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...origLocation, assign: assignSpy, href: "http://localhost/admin" },
    });
    try {
      await expect(apiCall("/admin/x")).rejects.toBeInstanceOf(ApiError);
      expect(authStore.get()).toBeNull();
      expect(assignSpy).toHaveBeenCalled();
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: origLocation });
    }
  });

  it("subscribes to SSE with Authorization and parses data frames", async () => {
    authStore.set("my-token");
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('event: TASK_SNAPSHOT\n'));
        controller.enqueue(new TextEncoder().encode('data: {"taskId":"task1","status":"RUNNING"}\n\n'));
        controller.close();
      },
    });
    fetchMock.mockResolvedValueOnce(new Response(stream, {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    }));
    const events: unknown[] = [];

    const subscription = subscribeEventStream("/api/processing-tasks/task1/events", {
      lastEventId: "task1:1",
      onEvent: (event) => events.push(event),
      onFallback: vi.fn(),
    });
    await waitFor(() => expect(events).toHaveLength(1));
    subscription.close();

    const [, init] = fetchMock.mock.calls[0]!;
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers.Accept).toBe("text/event-stream");
    expect(headers.Authorization).toBe("Bearer my-token");
    expect(headers["Last-Event-Id"]).toBe("task1:1");
    expect(events[0]).toMatchObject({ taskId: "task1", status: "RUNNING" });
  });
});

async function waitFor(assertion: () => void): Promise<void> {
  const deadline = Date.now() + 1000;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      assertion();
      return;
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  }
  throw lastError;
}
