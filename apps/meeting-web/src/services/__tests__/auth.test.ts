import { describe, it, expect, beforeAll, afterAll, afterEach, vi, beforeEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import type { ApiResponse, AuthUser } from "@shared/api/types";
import { useAuth, resetAuthForTests } from "../auth";
import * as api from "@shared/api/client";

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("useAuth", () => {
  it("should initialize as loading then authenticate when /auth/me succeeds", async () => {
    server.use(
      http.get("/api/auth/me", () =>
        HttpResponse.json<ApiResponse<AuthUser>>({
          success: true,
          data: {
            userId: "user_01",
            tenantId: "tenant_01",
            displayName: "Test User",
            roles: ["admin"],
            permissions: ["meeting:read"],
          },
          error: null,
          requestId: "req_01",
          traceId: "trace_01",
        })
      )
    );

    const { result } = renderHook(() => useAuth());

    expect(result.current.isLoading).toBe(true);
    expect(result.current.isAuthenticated).toBe(false);

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user?.userId).toBe("user_01");
  });

  it("should set isAuthenticated to false when /auth/me returns 401", async () => {
    server.use(
      http.get("/api/auth/me", () =>
        HttpResponse.json<ApiResponse>(
          {
            success: false,
            data: null,
            error: { code: "UNAUTHORIZED", message: "Not logged in", retryable: false },
            requestId: "req_02",
            traceId: "trace_02",
          },
          { status: 401 }
        )
      )
    );

    const { result } = renderHook(() => useAuth());

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
  });

  it("should login and set user after successful POST /auth/login", async () => {
    server.use(
      http.get("/api/auth/me", () =>
        HttpResponse.json<ApiResponse>(
          {
            success: false,
            data: null,
            error: { code: "UNAUTHORIZED", message: "Not logged in", retryable: false },
            requestId: "req_03",
            traceId: "trace_03",
          },
          { status: 401 }
        )
      ),
      http.post("/api/auth/login", () =>
        HttpResponse.json<ApiResponse<{ accessToken: string; expiresAt: string; user: AuthUser }>>({
          success: true,
          data: {
            accessToken: "token_123",
            expiresAt: new Date(Date.now() + 3600000).toISOString(),
            user: {
              userId: "user_02",
              tenantId: "tenant_02",
              displayName: "Logged In User",
              roles: ["user"],
              permissions: ["meeting:read"],
            },
          },
          error: null,
          requestId: "req_04",
          traceId: "trace_04",
        })
      )
    );

    const { result } = renderHook(() => useAuth());
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await act(async () => {
      await result.current.login("user_02", "password");
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user?.displayName).toBe("Logged In User");
  });

  it("should logout and clear user after POST /auth/logout", async () => {
    server.use(
      http.get("/api/auth/me", () =>
        HttpResponse.json<ApiResponse<AuthUser>>({
          success: true,
          data: {
            userId: "user_03",
            tenantId: "tenant_03",
            displayName: "To Logout",
            roles: ["admin"],
            permissions: ["meeting:delete"],
          },
          error: null,
          requestId: "req_05",
          traceId: "trace_05",
        })
      ),
      http.post("/api/auth/logout", () =>
        HttpResponse.json<ApiResponse>({
          success: true,
          data: null,
          error: null,
          requestId: "req_06",
          traceId: "trace_06",
        })
      )
    );

    const { result } = renderHook(() => useAuth());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAuthenticated).toBe(true);

    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
  });
});

describe('useAuth refresh flow', () => {
  beforeEach(() => {
    resetAuthForTests();
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('redirects to login on AUTH_REQUIRED', async () => {
    const mockReplace = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '', replace: mockReplace },
      writable: true,
    });

    vi.spyOn(api, 'getCurrentUser').mockRejectedValue({
      code: 'AUTH_REQUIRED',
      message: '会话已过期',
    });

    const { result } = renderHook(() => useAuth());

    await waitFor(() => {
      expect(result.current.isAuthenticated).toBe(false);
    });

    // Trigger error event
    const errorEvent = new ErrorEvent('error', {
      error: { code: 'AUTH_REQUIRED' },
    });
    window.dispatchEvent(errorEvent);

    await waitFor(() => {
      expect(window.location.href).toBe('/login');
    });
  });

  it('clears token on logout', async () => {
    vi.spyOn(api, 'logout').mockResolvedValue(undefined);
    const setTokenSpy = vi.spyOn(api, 'setAuthToken');

    const mockReplace = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '', replace: mockReplace },
      writable: true,
    });

    const { result } = renderHook(() => useAuth());

    await result.current.logout();

    expect(setTokenSpy).toHaveBeenCalledWith(null);
    expect(window.location.href).toBe('/login');
  });
});
