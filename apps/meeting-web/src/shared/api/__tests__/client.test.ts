import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { createSpeakerEnrollment, createSpeakerProfile, rejectMeetingSpeaker, releaseLegalHold, getCurrentUser, setAuthToken } from "../client";
import { server } from "../mocks/server";
import type { ApiResponse } from "../types";

describe("api client", () => {
  it("sends the OpenAPI speaker profile request shape", async () => {
    const captured: { body?: unknown; idempotencyKey?: string | null } = {};
    server.use(
      http.post("/api/speaker-profiles", async ({ request }) => {
        captured.body = await request.json();
        captured.idempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json<ApiResponse<unknown>>({
          success: true,
          data: {
            speakerProfileId: "spk_01",
            tenantId: "tenant_01",
            personId: "person_01",
            displayName: "Alice",
            consentStatus: "ACTIVE",
            consentSource: "USER_ENROLLMENT",
            consentVersion: "v1",
            revokedAt: null,
            deletedAt: null,
            createdAt: "2026-05-12T11:00:00Z",
            updatedAt: "2026-05-12T11:00:00Z",
          },
          error: null,
          requestId: "req_01",
          traceId: "trace_01",
        });
      }),
    );

    await createSpeakerProfile({ personId: "person_01", displayName: "Alice" });

    expect(captured.body).toEqual({
      personId: "person_01",
      displayName: "Alice",
      consentReference: "USER_ENROLLMENT:v1",
    });
    expect(captured.idempotencyKey).toMatch(/^create-speaker-profile_/);
  });

  it("sends the OpenAPI speaker enrollment request shape", async () => {
    const captured: { body?: unknown; idempotencyKey?: string | null } = {};
    server.use(
      http.post("/api/speaker-profiles/:profileId/enrollments", async ({ request }) => {
        captured.body = await request.json();
        captured.idempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json<ApiResponse<unknown>>({
          success: true,
          data: {
            enrollmentId: "spe_01",
            speakerProfileId: "spk_01",
            tenantId: "tenant_01",
            sourceAudioFileId: "file_01",
            enrollmentStatus: "PENDING",
            qualityScore: null,
            modelVersion: null,
            errorCode: null,
            createdAt: "2026-05-12T11:00:00Z",
            updatedAt: "2026-05-12T11:00:00Z",
          },
          error: null,
          requestId: "req_01",
          traceId: "trace_01",
        });
      }),
    );

    await createSpeakerEnrollment("spk_01", "file_01");

    expect(captured.body).toEqual({
      audioFileId: "file_01",
      consentReference: "USER_ENROLLMENT:v1",
    });
    expect(captured.idempotencyKey).toMatch(/^create-speaker-enrollment_/);
  });

  it("sends the OpenAPI speaker rejection request shape", async () => {
    const captured: { body?: unknown; idempotencyKey?: string | null } = {};
    server.use(
      http.post("/api/meetings/:meetingId/speakers/:speakerLabel/reject", async ({ request }) => {
        captured.body = await request.json();
        captured.idempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json<ApiResponse<unknown>>({
          success: true,
          data: null,
          error: null,
          requestId: "req_01",
          traceId: "trace_01",
        });
      }),
    );

    await rejectMeetingSpeaker("meeting_01", "SPEAKER_00");

    expect(captured.body).toEqual({ reason: "user_rejected" });
    expect(captured.idempotencyKey).toMatch(/^reject-meeting-speaker_/);
  });

  it("sends the OpenAPI legal hold release request shape", async () => {
    const captured: { body?: unknown; idempotencyKey?: string | null } = {};
    server.use(
      http.delete("/api/legal-holds/:legalHoldId", async ({ request }) => {
        captured.body = await request.json();
        captured.idempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json<ApiResponse<unknown>>({
          success: true,
          data: null,
          error: null,
          requestId: "req_01",
          traceId: "trace_01",
        });
      }),
    );

    await releaseLegalHold("lh_01", { reason: "case closed" });

    expect(captured.body).toEqual({ reason: "case closed" });
    expect(captured.idempotencyKey).toMatch(/^release-legal-hold_/);
  });
});

describe('API client 401 interceptor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn();
    // Mock cookie
    Object.defineProperty(document, 'cookie', {
      writable: true,
      value: 'XSRF-TOKEN=test-csrf-token',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    setAuthToken(null);
  });

  it('retries request after successful refresh on 401', async () => {
    setAuthToken('old-token');

    const fetchMock = global.fetch as ReturnType<typeof vi.fn>;

    // First call: 401 response
    fetchMock.mockResolvedValueOnce({
      status: 401,
      ok: false,
    } as Response);

    // Second call: successful refresh
    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: {
          accessToken: 'new-token',
          expiresAt: '2026-06-14T00:00:00Z',
        },
      }),
    } as Response);

    // Third call: successful retry with new token
    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: {
          userId: 'user-1',
          username: 'test',
          tenantId: 'tenant-1',
          role: 'USER',
        },
      }),
    } as Response);

    const result = await getCurrentUser();

    expect(result).toBeDefined();
    expect(result.userId).toBe('user-1');
    expect(fetchMock).toHaveBeenCalledTimes(3);

    // Verify the retry used the new token
    const retryCall = fetchMock.mock.calls[2];
    expect(retryCall[1].headers.Authorization).toBe('Bearer new-token');
  });

  it('throws AUTH_REQUIRED after refresh failure', async () => {
    setAuthToken('old-token');

    const fetchMock = global.fetch as ReturnType<typeof vi.fn>;

    // First call: 401 response
    fetchMock.mockResolvedValueOnce({
      status: 401,
      ok: false,
    } as Response);

    // Second call: refresh fails with 401
    fetchMock.mockResolvedValueOnce({
      status: 401,
      ok: true,
      json: async () => ({
        success: false,
        error: {
          code: 'REFRESH_TOKEN_EXPIRED',
          message: 'Refresh token expired',
          retryable: false,
        },
      }),
    } as Response);

    await expect(getCurrentUser()).rejects.toMatchObject({
      code: 'AUTH_REQUIRED',
      retryable: false,
      message: '认证已过期，请重新登录',
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('uses single-flight refresh for concurrent 401s', async () => {
    setAuthToken('old-token');

    const fetchMock = global.fetch as ReturnType<typeof vi.fn>;

    // First and second calls: both get 401
    fetchMock.mockResolvedValueOnce({
      status: 401,
      ok: false,
    } as Response);

    fetchMock.mockResolvedValueOnce({
      status: 401,
      ok: false,
    } as Response);

    // Third call: ONE successful refresh (single-flight!)
    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: {
          accessToken: 'new-token',
          expiresAt: '2026-06-14T00:00:00Z',
        },
      }),
    } as Response);

    // Fourth and fifth calls: both retries succeed
    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: {
          userId: 'user-1',
          username: 'test1',
          tenantId: 'tenant-1',
          role: 'USER',
        },
      }),
    } as Response);

    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: {
          userId: 'user-2',
          username: 'test2',
          tenantId: 'tenant-1',
          role: 'USER',
        },
      }),
    } as Response);

    // Make two concurrent requests
    const [result1, result2] = await Promise.all([
      getCurrentUser(),
      getCurrentUser(),
    ]);

    expect(result1).toBeDefined();
    expect(result1.username).toBe('test1');
    expect(result2).toBeDefined();
    expect(result2.username).toBe('test2');

    // Critical assertion: 5 calls total (2x401 + 1xrefresh + 2xretry)
    // This proves only ONE refresh happened despite TWO concurrent 401s
    expect(fetchMock).toHaveBeenCalledTimes(5);

    // Verify both retries used the new token
    const retry1 = fetchMock.mock.calls[3];
    const retry2 = fetchMock.mock.calls[4];
    expect(retry1[1].headers.Authorization).toBe('Bearer new-token');
    expect(retry2[1].headers.Authorization).toBe('Bearer new-token');
  });
});
