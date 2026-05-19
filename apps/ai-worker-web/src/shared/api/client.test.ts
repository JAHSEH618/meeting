import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { ApiError, apiCall } from "@/shared/api/client";
import { authStore } from "@/shared/auth/store";

describe("apiCall", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    authStore.clear();
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
});
