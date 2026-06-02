import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { createSpeakerEnrollment } from "../client";
import { server } from "../mocks/server";
import type { ApiResponse } from "../types";

describe("api client", () => {
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
});
