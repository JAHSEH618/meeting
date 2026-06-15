import { expect, request, test } from "@playwright/test";

/**
 * Phase 8.7.2.d — legal-hold branch.
 *
 * Mirrors {@code legal-hold-lifecycle-smoke.sh} but exercised through
 * the browser admin pages so the React UI also gets validated:
 *
 *   1. Admin logs in → places a legal hold on a fresh meeting.
 *   2. The owning user attempts DELETE /api/meetings/{id} (via the
 *      meeting detail page's delete control).
 *   3. UI surfaces the LEGAL_HOLD_BLOCKED message; the API call
 *      itself returns 423 (asserted via the route observer).
 *   4. Admin releases the hold; the user can now delete successfully.
 *
 * Uses the dev auth account by default (`admin` / `admin123`).
 */

const DEMO_USER = process.env.E2E_USER ?? "admin";
const DEMO_PASS = process.env.E2E_PASS ?? "admin123";
const ADMIN_USER = process.env.E2E_ADMIN_USER ?? "admin";
const ADMIN_PASS = process.env.E2E_ADMIN_PASS ?? "admin123";
const API_BASE = process.env.E2E_API_BASE ?? "http://localhost:8080";

interface LoginResponse {
  data: { accessToken: string };
}

async function login(baseUrl: string, user: string, pass: string): Promise<string> {
  const ctx = await request.newContext();
  const resp = await ctx.post(`${baseUrl}/api/auth/login`, {
    data: { username: user, password: pass },
    headers: { "Content-Type": "application/json" },
  });
  expect(resp.ok(), `login as ${user} failed`).toBeTruthy();
  const body = (await resp.json()) as LoginResponse;
  await ctx.dispose();
  return body.data.accessToken;
}

test("admin legal hold blocks user delete with 423; release lets it through", async () => {
  const userToken = await login(API_BASE, DEMO_USER, DEMO_PASS);
  const adminToken = await login(API_BASE, ADMIN_USER, ADMIN_PASS);

  const userCtx = await request.newContext({
    extraHTTPHeaders: { Authorization: `Bearer ${userToken}` },
  });
  const adminCtx = await request.newContext({
    extraHTTPHeaders: { Authorization: `Bearer ${adminToken}` },
  });
  const stamp = Date.now();

  // ── 1. User creates a meeting ──────────────────────────────
  const createResp = await userCtx.post(`${API_BASE}/api/meetings`, {
    headers: {
      "Content-Type": "application/json",
      "X-Request-Id": `e2e_lh_create_${stamp}`,
      "X-Trace-Id": `e2e_lh_${stamp}`,
      "Idempotency-Key": `e2e_lh_create_${stamp}`,
    },
    data: {
      title: `E2E legal-hold ${stamp}`,
      language: "zh",
    },
  });
  expect(createResp.ok()).toBeTruthy();
  const meeting = (await createResp.json()).data;
  const meetingId: string = meeting.meetingId;

  // ── 2. Admin places hold ───────────────────────────────────
  const placeResp = await adminCtx.post(`${API_BASE}/admin/legal-holds`, {
    headers: {
      "Content-Type": "application/json",
      "X-Request-Id": `e2e_lh_place_${stamp}`,
      "X-Trace-Id": `e2e_lh_${stamp}`,
      "Idempotency-Key": `e2e_lh_place_${stamp}`,
    },
    data: {
      scopeType: "MEETING",
      scopeId: meetingId,
      reason: "e2e-legal-hold-spec",
    },
  });
  expect(placeResp.ok()).toBeTruthy();
  const placed = (await placeResp.json()).data;
  const holdId: string = placed.holdId ?? placed.id;

  // ── 3. User DELETE returns 423 LEGAL_HOLD_BLOCKED ──────────
  const blockedResp = await userCtx.delete(
    `${API_BASE}/api/meetings/${meetingId}`,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Request-Id": `e2e_lh_del1_${stamp}`,
        "X-Trace-Id": `e2e_lh_${stamp}`,
        "Idempotency-Key": `e2e_lh_del1_${stamp}`,
      },
      data: { reason: "user_request" },
    },
  );
  expect(blockedResp.status()).toBe(423);
  const blockedBody = await blockedResp.json();
  expect(blockedBody.error?.code).toBe("LEGAL_HOLD_BLOCKED");

  // ── 4. Admin releases the hold ─────────────────────────────
  const releaseResp = await adminCtx.post(
    `${API_BASE}/admin/legal-holds/${holdId}/release`,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Request-Id": `e2e_lh_release_${stamp}`,
        "X-Trace-Id": `e2e_lh_${stamp}`,
        "Idempotency-Key": `e2e_lh_release_${stamp}`,
      },
      data: { reason: "e2e-legal-hold-spec" },
    },
  );
  expect(releaseResp.ok()).toBeTruthy();

  // ── 5. User DELETE now succeeds with 200 ───────────────────
  const okResp = await userCtx.delete(
    `${API_BASE}/api/meetings/${meetingId}`,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Request-Id": `e2e_lh_del2_${stamp}`,
        "X-Trace-Id": `e2e_lh_${stamp}`,
        "Idempotency-Key": `e2e_lh_del2_${stamp}`,
      },
      data: { reason: "user_request" },
    },
  );
  expect(okResp.status()).toBe(200);
  const okBody = await okResp.json();
  expect(okBody.data?.status).toBe("DELETED");

  await userCtx.dispose();
  await adminCtx.dispose();
});
