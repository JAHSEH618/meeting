# Meeting Web Remediation Plan (Review P3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 3 Critical and 15 Important findings from the 2026-06-12 meeting-web code review: stop voiceprint enrollment from hijacking real meetings, stop SSE step events from terminating the task page, add 401/refresh/logout handling, enforce per-user-action idempotency keys, fix the cache-invalidation matrix, harden SSE reconnect/exports streaming, fix upload cancel/offset/finalize bugs, add confirm dialogs, remove hardcoded credentials, and virtualize the transcript list.

**Architecture:** All fixes stay inside `apps/meeting-web` except two contract touch-ups in `packages/meeting-contracts` (stale `/files` description; `UpdateTranscriptSegmentRequest` drift) and one stale line in `apps/meeting-web/SPEC.md`. Java is untouched (the `/files` MIME allowlist already accepts audio — verified, see "Verification notes"). New shared infrastructure: a `useApiMutation` wrapper (action-scoped idempotency keys + uniform error surface), an `invalidateAfter` event→query-key matrix, a fetch-based SSE helper shared by task and export streams, and `@tanstack/react-virtual` for the transcript.

**Tech Stack:** React 18.3, Vite 5, TypeScript strict, TanStack Query v5, Zustand, Vitest + React Testing Library + MSW v2, new dependency `@tanstack/react-virtual` v3.

**Branch:** `fix/review-remediation-p3-meeting-web`
**Source review:** 2026-06-12 four-workspace code review — this volume fixes meeting-web Critical #1–#3 and Important #1–#15.

---

## Verification notes (every claim was checked against source before planning)

All Critical/Important findings were confirmed at the cited lines. Four findings needed adjustment after inspection:

1. **C3/D3 — there is no refresh endpoint anywhere.** `apps/meeting-web/SPEC.md` §5.2 specifies the *policy* (single-flight 401 refresh, HttpOnly refresh cookie, non-HttpOnly CSRF token echoed via `X-CSRF-Token`, fail → clear memory token + return to login) but names **no endpoint and no cookie name**. `public-api.yaml` has only `/auth/login|logout|me`; `docs/app-api-contracts.md` §4.1 shows a `refreshToken` field in the login response example but defines no refresh endpoint; zero Java code mentions refresh or CSRF. **Plan:** implement the full client-side architecture per SPEC §5.2 (single-flight, retry-once, fail-closed) against `POST /api/auth/refresh` with CSRF cookie `XSRF-TOKEN` (Spring Security's `CookieCsrfTokenRepository` default — the backend is Spring Boot), both isolated as constants in one place. Until the backend ships the endpoint, every 401 deterministically takes the *refresh-failed* path: token cleared, auth store reset, redirect to `/login` — which is the user-visible fix for "stranded on broken pages". Documented as a follow-up for meeting-api.
2. **D1 — Java `/files` MIME allowlist accepts audio; no Java task needed, but `audio/webm` is excluded.** `GenericFileUploadApplicationService.java:42-52` whitelists `audio/wav`, `audio/mpeg`, `audio/x-m4a`, `audio/flac` (mirrored as a closed `contentType` **enum** in `CreateFileUploadRequest`, public-api.yaml:2400-2411). `MediaRecorder` records `audio/webm`, which would 415. The production BFF (`apps/ai-worker/ai_worker/admin/enrollment.py:225-227,274`) ships *all* enrollment audio as `contentType: "audio/wav"` / `enroll-{id}.wav` regardless of container — the worker sniffs the real container, not the label. **Plan:** mirror the BFF exactly (recordings labeled `audio/wav`; upload-tab files pass their real MIME when it is one of the four allowed values, otherwise fall back to `audio/wav`). Honest `audio/webm` support would require contract-enum + Java-whitelist + 3-language codegen changes — out of P3 scope, listed as follow-up. Only the stale `/files` description is amended.
3. **I2 deepened:** the contract's `DELETE /speaker-profiles/{profileId}` requires not only `Idempotency-Key` but also a JSON body `DeleteSpeakerProfileRequest {reason}` (public-api.yaml:1076-1081, 3374-3380) which the client never sends. Both fixed together. `POST /rag/query` (public-api.yaml:1559) and `POST /auth/logout` (public-api.yaml:90) **do** declare `Idempotency-Key` (`required: true`, public-api.yaml:1964-1968) — so keys are added, not skipped.
4. **D6 — `GET /meetings/{meetingId}/transcript` has no cursor/limit parameters** (public-api.yaml:849-871). Per the locked decision: virtualize only, do **not** invent API params; cursor pagination recorded as a contract follow-up.

Bonus latent bug confirmed and killed by the C1 rewrite: `SpeakerEnrollPanel.tsx:170` sends `partSizeBytes: fileBlob.size * 2`, which violates the contract minimum `1048576` for clips under 512 KiB. The new flow omits `partSizeBytes` (server clamps to `max(fileSize, 5 MiB)` ⇒ always exactly one part).

---

## File Structure

```text
apps/meeting-web/
  package.json                                          # modify (+ @tanstack/react-virtual)
  SPEC.md                                               # modify (delete stale §10.2 item 9)
  src/shared/api/client.ts                              # modify (C2-adjacent SSE, C3, I1, I2, I7, C1 /files fns)
  src/shared/api/useApiMutation.ts                      # create (D4)
  src/shared/api/invalidations.ts                       # create (D7)
  src/shared/api/mocks/handlers.ts                      # modify (+ /api/files trio, signed PUT)
  src/shared/api/__tests__/client.auth.test.ts          # create (C3)
  src/shared/api/__tests__/client.sse.test.ts           # create (I7)
  src/shared/api/__tests__/useApiMutation.test.tsx      # create (I1)
  src/shared/api/__tests__/invalidations.test.ts        # create (D7)
  src/shared/utils/sse-reducer.ts                       # modify (C2)
  src/shared/utils/__tests__/sse-reducer.test.ts        # modify (C2 regression tests)
  src/shared/utils/error-mapper.ts                      # modify (minor: RAG_RATE_LIMITED, server-message fallback, file-upload codes)
  src/shared/queries/useTaskEventsStream.ts             # modify (I7: getLastEventId)
  src/shared/queries/queryClient.ts                     # modify (minor: retry predicate)
  src/shared/components/ConfirmDialog.tsx               # create (I14)
  src/services/auth.ts                                  # modify (C3: session-expired wiring)
  src/app/App.tsx                                       # modify (C3: Shell logout button)
  src/app/__tests__/ShellLogout.test.tsx                # create (C3)
  src/features/auth/LoginPage.tsx                       # modify (I15)
  src/features/auth/__tests__/LoginPage.test.tsx        # modify (I15: type credentials explicitly)
  src/features/speakers/SpeakerEnrollPanel.tsx          # rewrite (C1)
  src/features/speakers/__tests__/SpeakerEnrollPanel.test.tsx  # rewrite (C1)
  src/features/speakers/SpeakerProfileCard.tsx          # modify (I14)
  src/features/speakers/__tests__/SpeakerProfileCard.confirm.test.tsx  # create (I14)
  src/features/speakers/MeetingSpeakerConfirmPage.tsx   # modify (I6)
  src/features/transcript/TranscriptPage.tsx            # modify (I3 virtualization, I4 conflict copy)
  src/features/transcript/queries.ts                    # modify (I4, I5 via invalidateAfter)
  src/features/transcript/__tests__/TranscriptPage.test.tsx        # modify
  src/features/transcript/__tests__/TranscriptPage.virtual.test.tsx # create (I3)
  src/features/minutes/queries.ts                       # modify (I5)
  src/features/meetings/MeetingCreatePage.tsx           # modify (I12)
  src/features/meetings/queries.ts                      # modify (I12: useCreateMeeting → wrapper + invalidateAfter)
  src/features/tasks/queries.ts                         # modify (I1/I11: wrapper migration)
  src/features/tasks/TaskProgressPage.tsx               # modify (I11: render mutation errors)
  src/features/tasks/__tests__/TaskProgressPage.test.tsx # modify (I11)
  src/features/audio/AudioUploadPage.tsx                # modify (I8, I9, I10)
  src/features/audio/upload-reducer.ts                  # modify (I8: terminal-state guards)
  src/features/audio/__tests__/upload-reducer.test.ts   # modify (I8)
  src/features/audio/__tests__/AudioUploadPage.test.tsx # modify (I8/I10)
  src/features/exports/ExportsPage.tsx                  # modify (I13, minor cancel pending; D4 createExport)
  src/features/exports/__tests__/ExportsPage.sse.test.tsx # modify (I13)
  src/features/speakers/queries.ts                      # modify (I2: delete reason param passthrough)
packages/meeting-contracts/
  openapi/public-api.yaml                               # modify (/files description; UpdateTranscriptSegmentRequest drift)
  (regenerated codegen outputs in all four consumers — commit drift)
```

---

## Task 1: C2 — sse-reducer writes step status into task status; TASK_COMPLETED hardcodes SUCCEEDED

The smallest, highest-leverage fix. `src/shared/utils/sse-reducer.ts:53-57` copies the **step** status from `TASK_STEP_UPDATED` into task-level `status`, so the first `SUCCEEDED` step makes `useTaskEventsStream` treat the task as terminal (stream closed, polling disabled, cancel disabled) and a retryable step `FAILED` shows the whole task as failed. `sse-reducer.ts:88-93` hardcodes `TASK_COMPLETED → "SUCCEEDED"`, losing `PARTIAL_SUCCEEDED`.

**Files:**
- Modify: `src/shared/utils/sse-reducer.ts`
- Modify: `src/shared/utils/__tests__/sse-reducer.test.ts`

- [ ] **Step 1.1: Write the failing regression tests**

Append to the existing `describe("sseReducer", ...)` block in `src/shared/utils/__tests__/sse-reducer.test.ts` (it already provides `baseState`, `baseEvent`, `makeStep`):

```ts
  // ── Review P3 C2 regressions ─────────────────────────────────────

  it("TASK_STEP_UPDATED with a SUCCEEDED step does NOT change task-level status", () => {
    const state: TaskSnapshot = {
      ...baseState,
      steps: [makeStep("AUDIO_PREPROCESS", "RUNNING", 90), makeStep("ASR", "PENDING")],
    };
    const event: TaskEvent = {
      ...baseEvent("TASK_STEP_UPDATED", "task_01"),
      status: "SUCCEEDED", // step status, NOT task status
      stepName: "AUDIO_PREPROCESS",
      progress: 100,
      completedSteps: ["AUDIO_PREPROCESS"],
    };
    const next = sseReducer(state, event);
    expect(next.status).toBe("RUNNING"); // task keeps running
    expect(next.steps[0]!.status).toBe("SUCCEEDED");
    expect(next.completedSteps).toEqual(["AUDIO_PREPROCESS"]);
  });

  it("TASK_STEP_UPDATED with a FAILED retryable step does NOT fail the whole task", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_STEP_UPDATED", "task_01"),
      status: "FAILED",
      stepName: "ASR",
      progress: 10,
    };
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("RUNNING");
    expect(next.steps[0]!.status).toBe("FAILED");
  });

  it("TASK_COMPLETED takes status from the event (PARTIAL_SUCCEEDED is preserved)", () => {
    const event: TaskEvent = {
      ...baseEvent("TASK_COMPLETED", "task_01"),
      status: "PARTIAL_SUCCEEDED",
    };
    const next = sseReducer(baseState, event);
    expect(next.status).toBe("PARTIAL_SUCCEEDED");
    expect(next.phase).toBe("TERMINAL");
  });
```

- [ ] **Step 1.2: Run and confirm the three new tests fail**

```bash
cd apps/meeting-web
npx vitest run src/shared/utils/__tests__/sse-reducer.test.ts
```

Expected: 3 failures — `expected 'SUCCEEDED' to be 'RUNNING'`, `expected 'FAILED' to be 'RUNNING'`, `expected 'SUCCEEDED' to be 'PARTIAL_SUCCEEDED'`. The 8 pre-existing tests stay green.

- [ ] **Step 1.3: Fix the reducer**

In `src/shared/utils/sse-reducer.ts`, replace the two cases:

```ts
    case "TASK_STEP_UPDATED":
      // event.status here is the STEP's status — task-level status only
      // changes via TASK_SNAPSHOT / TASK_COMPLETED / TASK_FAILED / TASK_CANCELLED.
      return {
        ...state,
        currentStep: event.stepName ?? state.currentStep,
        steps: state.steps.map((s) =>
          s.stepName === event.stepName
            ? { ...s, status: event.status as TaskStep["status"], progress: event.progress ?? s.progress }
            : s
        ),
        completedSteps: event.completedSteps ?? state.completedSteps,
      };
```

```ts
    case "TASK_COMPLETED":
      return {
        ...state,
        // Contract requires event.status on every TaskEvent; it carries
        // SUCCEEDED or PARTIAL_SUCCEEDED here. Never hardcode.
        status: event.status || "SUCCEEDED",
        phase: "TERMINAL" as ProcessingTaskPhase,
      };
```

- [ ] **Step 1.4: Run the full suite and type check**

```bash
npx vitest run src/shared/utils/__tests__/sse-reducer.test.ts   # 11 passing
npm test
npx tsc --noEmit
```

- [ ] **Step 1.5: Commit**

```bash
git add -A && git commit -m "fix(web): C2 sse-reducer no longer writes step status into task status; TASK_COMPLETED honors event.status"
```

---

## Task 2: C3 — central 401 handling (single-flight refresh) + logout entry point

`client.ts` has no 401 interception at all; `useAuth().logout` (`src/services/auth.ts:44-51`) is dead code — no component renders it. Per SPEC §5.2: single-flight refresh, `X-CSRF-Token`, refresh failure → clear memory token + back to `/login`. See Verification note 1: the refresh endpoint does not exist server-side yet, so the constants are isolated and the failure path (clean logout) is today's deterministic behavior.

**Files:**
- Modify: `src/shared/api/client.ts`
- Modify: `src/services/auth.ts`
- Modify: `src/app/App.tsx`
- Create: `src/shared/api/__tests__/client.auth.test.ts`
- Create: `src/app/__tests__/ShellLogout.test.tsx`

- [ ] **Step 2.1: Write the failing auth-client test**

Create `src/shared/api/__tests__/client.auth.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import {
  getMeeting,
  setAuthToken,
  setSessionExpiredHandler,
  type ApiClientError,
} from "@shared/api/client";

const envelope = (data: unknown) => ({
  success: true, data, error: null, requestId: "r", traceId: "t",
});
const unauthorized = () =>
  HttpResponse.json(
    { success: false, data: null, error: { code: "AUTH_REQUIRED", message: "expired", retryable: false, details: {} }, requestId: "r", traceId: "t" },
    { status: 401 },
  );

describe("client 401 handling (SPEC §5.2)", () => {
  beforeEach(() => setAuthToken("stale-token"));
  afterEach(() => {
    setSessionExpiredHandler(null);
    setAuthToken(null);
  });

  it("on 401 refreshes once (single-flight) and retries the original request with the new token", async () => {
    let refreshCalls = 0;
    let refreshed = false;
    server.use(
      http.post("/api/auth/refresh", () => {
        refreshCalls += 1;
        refreshed = true;
        return HttpResponse.json(envelope({ accessToken: "fresh-token", expiresAt: new Date(Date.now() + 3600_000).toISOString() }));
      }),
      http.get("/api/meetings/:meetingId", ({ request }) => {
        if (!refreshed || request.headers.get("Authorization") !== "Bearer fresh-token") {
          return unauthorized();
        }
        return HttpResponse.json(envelope({ meetingId: "mtg_01", title: "ok", status: "CREATED", language: "zh", tenantId: "t", transcriptVersion: 0, minutesVersion: 0, createdAt: "2026-06-12T00:00:00Z" }));
      }),
    );

    // Two concurrent 401s must share ONE in-flight refresh.
    const [a, b] = await Promise.all([getMeeting("mtg_01"), getMeeting("mtg_01")]);
    expect(a.meetingId).toBe("mtg_01");
    expect(b.meetingId).toBe("mtg_01");
    expect(refreshCalls).toBe(1);
  });

  it("on refresh failure clears the token, fires the session-expired handler, and throws AUTH_REQUIRED", async () => {
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    server.use(
      http.post("/api/auth/refresh", () => unauthorized()),
      http.get("/api/meetings/:meetingId", () => unauthorized()),
    );

    await expect(getMeeting("mtg_01")).rejects.toMatchObject({ code: "AUTH_REQUIRED" } satisfies Partial<ApiClientError>);
    expect(expired).toHaveBeenCalledOnce();

    // Token was cleared: the next request goes out without Authorization.
    let sawAuthHeader: string | null = "unset";
    server.use(
      http.get("/api/meetings/:meetingId", ({ request }) => {
        sawAuthHeader = request.headers.get("Authorization");
        return unauthorized();
      }),
    );
    await getMeeting("mtg_01").catch(() => undefined);
    expect(sawAuthHeader).toBeNull();
  });

  it("sends X-CSRF-Token from the CSRF cookie on refresh", async () => {
    document.cookie = "XSRF-TOKEN=csrf-abc";
    let csrfHeader: string | null = null;
    server.use(
      http.post("/api/auth/refresh", ({ request }) => {
        csrfHeader = request.headers.get("X-CSRF-Token");
        return unauthorized();
      }),
      http.get("/api/meetings/:meetingId", () => unauthorized()),
    );
    await getMeeting("mtg_01").catch(() => undefined);
    expect(csrfHeader).toBe("csrf-abc");
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });
});
```

- [ ] **Step 2.2: Run and confirm failure**

```bash
npx vitest run src/shared/api/__tests__/client.auth.test.ts
```

Expected: compile error — `setSessionExpiredHandler` is not exported — then assertion failures once stubbed.

- [ ] **Step 2.3: Implement in `client.ts`**

Add below `setAuthToken` (keeping `generateId` for `X-Request-Id`/`X-Trace-Id`, which are intentionally per-call):

```ts
// ── 401 / refresh (SPEC §5.2) ──────────────────────────────────────
// NOTE: the backend does not expose /auth/refresh yet (verified 2026-06-12:
// absent from public-api.yaml and meeting-api). Until it ships, refresh
// deterministically fails and we fall through to the clean session-expired
// path (clear memory token → reset auth store → /login). Endpoint + cookie
// name are isolated here so backend wiring is a 2-constant change.
const REFRESH_PATH = "/auth/refresh";
const CSRF_COOKIE_NAME = "XSRF-TOKEN"; // Spring Security CookieCsrfTokenRepository default
const AUTH_EXEMPT_PATHS = new Set(["/auth/login", REFRESH_PATH]);

let refreshInFlight: Promise<boolean> | null = null;
let sessionExpiredHandler: (() => void) | null = null;

export function setSessionExpiredHandler(handler: (() => void) | null) {
  sessionExpiredHandler = handler;
}

function readCookie(name: string): string | null {
  const row = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return row ? decodeURIComponent(row.slice(name.length + 1)) : null;
}

async function refreshAccessToken(): Promise<boolean> {
  try {
    const headers: Record<string, string> = {
      Accept: "application/json",
      "X-Request-Id": generateId("req"),
      "X-Trace-Id": generateId("trace"),
    };
    const csrf = readCookie(CSRF_COOKIE_NAME);
    if (csrf) headers["X-CSRF-Token"] = csrf;
    const res = await fetch(`${API_BASE}${REFRESH_PATH}`, {
      method: "POST",
      headers,
      credentials: "include", // HttpOnly refresh cookie
    });
    if (!res.ok) return false;
    const json = (await res.json()) as ApiResponse<{ accessToken: string }>;
    if (!json.success || !json.data?.accessToken) return false;
    authToken = json.data.accessToken;
    return true;
  } catch {
    return false;
  }
}

function refreshOnce(): Promise<boolean> {
  // Single-flight: concurrent 401s share one refresh promise (SPEC §5.2 rule 4).
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

function sessionExpired(): ApiClientError {
  setAuthToken(null);
  sessionExpiredHandler?.();
  const error = new Error("登录状态已过期，请重新登录") as ApiClientError;
  error.code = "AUTH_REQUIRED";
  error.retryable = false;
  error.status = 401;
  return error;
}
```

Rework `request()` so the fetch is re-executable (stringify the body once, regenerate headers per attempt):

```ts
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  idempotencyKey?: string,
): Promise<T> {
  const payload = body !== undefined ? JSON.stringify(body) : undefined;

  const exec = async (): Promise<Response> => {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
      "X-Request-Id": generateId("req"),
      "X-Trace-Id": generateId("trace"),
    };
    if (authToken) headers["Authorization"] = `Bearer ${authToken}`;
    if (idempotencyKey && method !== "GET") headers["Idempotency-Key"] = idempotencyKey;
    try {
      return await fetch(`${API_BASE}${path}`, { method, headers, body: payload });
    } catch (cause) {
      const error = new Error("网络连接失败") as ApiClientError;
      error.code = "DEPENDENCY_UNAVAILABLE";
      error.retryable = true;
      error.details = { cause: String(cause) };
      throw error;
    }
  };

  let res = await exec();
  if (res.status === 401 && !AUTH_EXEMPT_PATHS.has(path)) {
    if (await refreshOnce()) res = await exec(); // retry the original request once
    if (res.status === 401) throw sessionExpired();
  }

  // …existing 404 mapping + envelope handling unchanged below…
```

- [ ] **Step 2.4: Wire the session-expired handler in `src/services/auth.ts`**

At module top level (this module is imported by `AuthGuard`, so it is always loaded):

```ts
import { setSessionExpiredHandler } from "@shared/api/client";

setSessionExpiredHandler(() => {
  useAuthStore.setState({ user: null, ready: true });
  if (!window.location.pathname.startsWith("/login")) {
    window.location.assign("/login");
  }
});
```

(`resetAuthForTests` stays as is; tests that need the handler set their own via `setSessionExpiredHandler`.)

- [ ] **Step 2.5: Add the logout button to `Shell` in `src/app/App.tsx`**

```tsx
import { Routes, Route, Navigate, NavLink, Outlet, Link, useNavigate } from "react-router-dom";
import { useAuth } from "@services/auth";

function Shell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const onLogout = async () => {
    await logout(); // clears token + store even if POST /auth/logout fails
    navigate("/login", { replace: true });
  };
  return (
    <div className="app-shell">
      {/* …existing rail content unchanged… */}
      <aside className="shell__rail" aria-label="主导航">
        {/* …brand, 新建会议, 工作 nav, 合规 nav… */}
        <div className="shell__rail-section" style={{ marginTop: "auto" }}>
          {user ? <span className="page-subtitle">{user.displayName}</span> : null}
          <button type="button" className="button button--ghost" onClick={() => void onLogout()}>
            退出登录
          </button>
        </div>
      </aside>
      <main id="main-content" className="shell__main"><Outlet /></main>
    </div>
  );
}
```

- [ ] **Step 2.6: Write the Shell logout test**

Create `src/app/__tests__/ShellLogout.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event"; // if unavailable, use fireEvent.click
import { MemoryRouter } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { createQueryClient } from "@shared/queries/queryClient";
import { useAuthStore } from "@shared/stores/auth";
import { App } from "../App";

it("Shell renders a logout button that calls POST /auth/logout and lands on /login", async () => {
  let logoutCalled = false;
  server.use(
    http.post("/api/auth/logout", () => {
      logoutCalled = true;
      return HttpResponse.json({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
    }),
  );
  useAuthStore.setState({
    user: { userId: "u1", tenantId: "t1", displayName: "测试用户", roles: ["admin"], permissions: [] },
    ready: true,
  });

  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/meetings"]}><App /></MemoryRouter>
    </QueryClientProvider>,
  );

  const button = await screen.findByRole("button", { name: "退出登录" });
  button.click();

  await waitFor(() => expect(logoutCalled).toBe(true));
  await waitFor(() => expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument());
});
```

(If `@testing-library/user-event` is not in devDependencies, stay with the `button.click()` form shown — do not add the dependency.)

- [ ] **Step 2.7: Run, type-check, commit**

```bash
npx vitest run src/shared/api/__tests__/client.auth.test.ts src/app/__tests__/ShellLogout.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): C3 single-flight 401 refresh with CSRF header, session-expired redirect, and Shell logout button"
```

---

## Task 3: D4 — `useApiMutation` wrapper + idempotency-key migration *(closes I1, I2, I11)*

**Mapping:** I1 (keys regenerated per HTTP call → action-scoped keys reused across retries), I2 (missing keys on `deleteSpeakerProfile`/`deleteDocument`/`logout`/`ragQuery` — all four are contract-required, see Verification note 3; plus the missing `{reason}` body on profile delete), I11 (retry/cancel errors swallowed → wrapper's uniform `errorMessage` rendered by `TaskProgressPage`).

**Files:**
- Create: `src/shared/api/useApiMutation.ts`
- Create: `src/shared/api/__tests__/useApiMutation.test.tsx`
- Modify: `src/shared/api/client.ts` (all write fns gain optional trailing `idempotencyKey`; fallback uses `generateIdempotencyKey`; missing keys/bodies added)
- Modify: `src/features/tasks/queries.ts`, `src/features/tasks/TaskProgressPage.tsx`, `src/features/tasks/__tests__/TaskProgressPage.test.tsx`
- Modify: `src/features/speakers/queries.ts` (delete passes `reason`), `src/features/exports/ExportsPage.tsx` (createExport via wrapper)

- [ ] **Step 3.1: Create the wrapper**

`src/shared/api/useApiMutation.ts`:

```ts
import { useRef } from "react";
import { useMutation } from "@tanstack/react-query";
import type { ApiClientError } from "@shared/api/client";
import { generateIdempotencyKey } from "@shared/utils/idempotency";
import { getUserMessage } from "@shared/utils/error-mapper";

export interface UseApiMutationOptions<TData, TVariables> {
  /** Idempotency-key prefix naming the logical user action, e.g. "retry-task". */
  actionKey: string;
  mutationFn: (variables: TVariables, idempotencyKey: string) => Promise<TData>;
  onSuccess?: (data: TData, variables: TVariables) => unknown;
  onError?: (error: ApiClientError, variables: TVariables) => void;
}

/**
 * Mutation wrapper enforcing SPEC §5.2 rule 5: one Idempotency-Key per
 * logical USER ACTION. A retry after failure reuses the previous key; a
 * new action after success generates a fresh one. Errors surface uniformly
 * via `errorMessage` (error-mapper with server-message fallback).
 */
export function useApiMutation<TData = unknown, TVariables = void>(
  options: UseApiMutationOptions<TData, TVariables>,
) {
  const retryKeyRef = useRef<string | null>(null);
  const mutation = useMutation<TData, ApiClientError, TVariables>({
    mutationFn: (variables) => {
      const key = retryKeyRef.current ?? generateIdempotencyKey(options.actionKey);
      retryKeyRef.current = key;
      return options.mutationFn(variables, key);
    },
    onSuccess: (data, variables) => {
      retryKeyRef.current = null; // next action ⇒ fresh key
      return options.onSuccess?.(data, variables);
    },
    onError: (error, variables) => options.onError?.(error, variables),
  });
  const error = (mutation.error as ApiClientError | null) ?? null;
  return {
    ...mutation,
    errorMessage: error ? getUserMessage(error.code ?? "", error.message) : null,
  };
}
```

(`getUserMessage` gains the second `fallback` parameter in the Minor triage — until then call it as `error.code ? getUserMessage(error.code) : error.message`; pick whichever lands first and keep `tsc` green.)

- [ ] **Step 3.2: Write the key-reuse test**

`src/shared/api/__tests__/useApiMutation.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { createQueryClient } from "@shared/queries/queryClient";
import { retryTask } from "@shared/api/client";
import { useApiMutation } from "../useApiMutation";

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={createQueryClient()}>{children}</QueryClientProvider>
);

it("reuses the Idempotency-Key when retrying a failed action, rotates after success", async () => {
  const seenKeys: string[] = [];
  let calls = 0;
  server.use(
    http.post("/api/processing-tasks/:taskId/retry", ({ request }) => {
      seenKeys.push(request.headers.get("Idempotency-Key") ?? "(none)");
      calls += 1;
      if (calls === 1) {
        return HttpResponse.json(
          { success: false, data: null, error: { code: "DEPENDENCY_UNAVAILABLE", message: "mq down", retryable: true, details: {} }, requestId: "r", traceId: "t" },
          { status: 503 },
        );
      }
      return HttpResponse.json({ success: true, data: { taskId: "task_01", status: "QUEUED", phase: null, attemptNo: 2, steps: [] }, error: null, requestId: "r", traceId: "t" });
    }),
  );

  const { result } = renderHook(
    () => useApiMutation<unknown, string>({
      actionKey: "retry-task",
      mutationFn: (taskId, key) => retryTask(taskId, "user_retry", key),
    }),
    { wrapper },
  );

  result.current.mutate("task_01"); // fails
  await waitFor(() => expect(result.current.errorMessage).not.toBeNull());
  result.current.mutate("task_01"); // user retries same action
  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  result.current.mutate("task_01"); // NEW action after success
  await waitFor(() => expect(calls).toBe(3));

  expect(seenKeys[0]).toBe(seenKeys[1]);     // retry reused the key
  expect(seenKeys[2]).not.toBe(seenKeys[1]); // fresh action got a fresh key
  expect(seenKeys.every((k) => k.startsWith("retry-task_"))).toBe(true);
});
```

Run: `npx vitest run src/shared/api/__tests__/useApiMutation.test.tsx` — fails until Step 3.3's `retryTask` signature lands (compile error on the 3-arg call).

- [ ] **Step 3.3: client.ts signature pass**

In `src/shared/api/client.ts`:
1. `import { generateIdempotencyKey } from "@shared/utils/idempotency";` — this retires the dead util.
2. Every write function gains an optional trailing `idempotencyKey?: string` and replaces its internal `generateId(...)` *for the Idempotency-Key only* with `idempotencyKey ?? generateIdempotencyKey("<same-prefix>")`. (`generateId` remains for `X-Request-Id`/`X-Trace-Id`, which must stay per-call.) Functions to convert: `createMeeting`, `createAudioUpload`, `createAudioUploadPart`, `completeAudioUpload`, `abortAudioUpload`, `createProcessingTask`, `retryTask`, `cancelTask`, `updateSegment`, `regenerateMinutes`, `acceptItem`, `rejectItem`, `createSpeakerProfile`, `revokeSpeakerProfile`, `createSpeakerEnrollment`, `confirmMeetingSpeaker`, `rejectMeetingSpeaker`, `reindexMeetingRag`, `reindexDocumentRag`, `createDocument`, `reindexDocument`, `createExport`, `cancelExport`, `revokeExportLink`, `createLegalHold`, `releaseLegalHold`, `createDeletionJob`, `createBreakGlassRequest`, `approveBreakGlassRequest`, `rejectBreakGlassRequest`. Representative diff:

```ts
export async function retryTask(taskId: string, reason = "user_retry", idempotencyKey?: string) {
  return request<import("@shared/api/types").ProcessingTask>(
    "POST",
    `/processing-tasks/${taskId}/retry`,
    { reason },
    idempotencyKey ?? generateIdempotencyKey("retry-task"),
  );
}
```

3. Add the four missing keys (all contract-required) — and the contract-required delete body:

```ts
export async function logout(idempotencyKey?: string) {
  return request<void>("POST", "/auth/logout", undefined, idempotencyKey ?? generateIdempotencyKey("logout"));
}

export async function deleteSpeakerProfile(profileId: string, reason = "user_request", idempotencyKey?: string) {
  // Contract: DELETE requires Idempotency-Key AND DeleteSpeakerProfileRequest{reason}.
  return request<void>(
    "DELETE",
    `/speaker-profiles/${profileId}`,
    { reason },
    idempotencyKey ?? generateIdempotencyKey("delete-speaker-profile"),
  );
}

export async function deleteDocument(documentId: string, idempotencyKey?: string) {
  return request<void>("DELETE", `/documents/${documentId}`, undefined, idempotencyKey ?? generateIdempotencyKey("delete-document"));
}

export async function ragQuery(data: import("@shared/api/types").RagQueryRequest, idempotencyKey?: string) {
  return request<import("@shared/api/types").RagQueryResponse>("POST", "/rag/query", data, idempotencyKey ?? generateIdempotencyKey("rag-query"));
}
```

- [ ] **Step 3.4: Migrate task retry/cancel and render their errors (I11)**

`src/features/tasks/queries.ts`:

```ts
import { useQueryClient } from "@tanstack/react-query";
import { cancelTask, retryTask } from "@shared/api/client";
import { useApiMutation } from "@shared/api/useApiMutation";
import type { ProcessingTask } from "@shared/api/types";

export function useRetryTask() {
  const qc = useQueryClient();
  return useApiMutation<ProcessingTask, string>({
    actionKey: "retry-task",
    mutationFn: (taskId, key) => retryTask(taskId, "user_retry", key),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}

export function useCancelTask() {
  const qc = useQueryClient();
  return useApiMutation<ProcessingTask, string>({
    actionKey: "cancel-task",
    mutationFn: (taskId, key) => cancelTask(taskId, "user_cancel", key),
    onSuccess: (_, taskId) => qc.invalidateQueries({ queryKey: ["task", taskId] }),
  });
}
```

`src/features/tasks/TaskProgressPage.tsx` — under the header, before the metrics grid:

```tsx
      {retry.errorMessage ? (
        <div className="banner banner--danger" role="alert">重试失败：{retry.errorMessage}</div>
      ) : null}
      {cancel.errorMessage ? (
        <div className="banner banner--danger" role="alert">取消失败：{cancel.errorMessage}</div>
      ) : null}
```

- [ ] **Step 3.5: Migrate `ExportsPage` createExport (named in I1)**

In `src/features/exports/ExportsPage.tsx`, replace the `creating`/`createError` state pair with:

```ts
const createMutation = useApiMutation<ExportJob, CreateExportInput>({
  actionKey: "create-export",
  mutationFn: (input, key) => createExport(meetingId, input, key),
  onSuccess: (job) => setJobs((prev) => [job, ...prev]),
});
// handleCreate body becomes: createMutation.mutate(input);
// render: createMutation.errorMessage instead of createError; createMutation.isPending instead of creating.
```

`src/features/speakers/queries.ts` `useDeleteSpeakerProfile` passes through unchanged (the default `reason` covers it) — it migrates to the wrapper in Task 8 alongside the confirm dialog.

- [ ] **Step 3.6: One I11 page-level test**

Add to `src/features/tasks/__tests__/TaskProgressPage.test.tsx`:

```tsx
it("renders retry failure instead of swallowing it (I11)", async () => {
  server.use(
    http.post("/api/processing-tasks/:taskId/retry", () =>
      HttpResponse.json(
        { success: false, data: null, error: { code: "TASK_ATTEMPT_CONFLICT", message: "raced", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 409 },
      ),
    ),
    // make the snapshot retryable so the button is enabled
    http.get("/api/processing-tasks/:taskId", () =>
      HttpResponse.json({ success: true, data: { taskId: "task_01", meetingId: "mtg_01", status: "FAILED", phase: "TERMINAL", attemptNo: 1, currentStep: null, lastErrorCode: "ASR_RUNTIME_ERROR", retryable: true, steps: [] }, error: null, requestId: "r", traceId: "t" }),
    ),
  );
  renderTaskProgressPage(); // reuse this file's existing render helper
  const retryButton = await screen.findByRole("button", { name: "重试" });
  fireEvent.click(retryButton);
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("任务尝试次数已变化"));
});
```

- [ ] **Step 3.7: Run, type-check, commit**

```bash
npx vitest run src/shared/api/__tests__/useApiMutation.test.tsx src/features/tasks/__tests__/TaskProgressPage.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): I1+I2+I11 action-scoped idempotency keys via useApiMutation; add contract-required keys/bodies; surface task retry/cancel errors"
```

---

## Task 4: D7 — central invalidation matrix *(closes I4, I5, I6, I12)*

**Mapping:** I4 (VERSION_CONFLICT banner lies — error branch never refetches), I5 (regenerate-minutes reads stale `["meeting", id]` ⇒ deterministic VERSION_CONFLICT), I6 (speaker confirm/reject leaves transcript cache stale), I12 (meeting creation bypasses `useCreateMeeting`, list never invalidated).

**Files:**
- Create: `src/shared/api/invalidations.ts`, `src/shared/api/__tests__/invalidations.test.ts`
- Modify: `src/features/transcript/queries.ts`, `src/features/transcript/TranscriptPage.tsx`, `src/features/minutes/queries.ts`, `src/features/speakers/MeetingSpeakerConfirmPage.tsx`, `src/features/meetings/queries.ts`, `src/features/meetings/MeetingCreatePage.tsx`
- Modify: `src/features/transcript/__tests__/TranscriptPage.test.tsx`

- [ ] **Step 4.1: Create the helper**

`src/shared/api/invalidations.ts`:

```ts
import type { QueryClient } from "@tanstack/react-query";

export type InvalidationEvent =
  | { type: "transcript-edited"; meetingId: string }
  | { type: "speaker-confirmed"; meetingId: string }
  | { type: "minutes-regenerated"; meetingId: string }
  | { type: "meeting-created" };

/**
 * Single source of truth for SPEC §6 cache-invalidation rules. Pages never
 * hand-roll invalidation lists; they describe what HAPPENED.
 * Note: ItemsPage/ExportsPage are not on TanStack Query yet — the
 * action-items/decisions/risks keys are registered ahead of their migration
 * (invalidating an unused key is a no-op).
 */
export function invalidateAfter(event: InvalidationEvent, queryClient: QueryClient): void {
  switch (event.type) {
    case "transcript-edited": {
      const { meetingId } = event;
      void queryClient.invalidateQueries({ queryKey: ["transcript", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["meeting", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["minutes", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["action-items", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["decisions", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["risks", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["rag"] });
      break;
    }
    case "speaker-confirmed": {
      const { meetingId } = event;
      void queryClient.invalidateQueries({ queryKey: ["transcript", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["meeting", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["rag"] });
      break;
    }
    case "minutes-regenerated": {
      const { meetingId } = event;
      void queryClient.invalidateQueries({ queryKey: ["minutes", meetingId] });
      void queryClient.invalidateQueries({ queryKey: ["meeting", meetingId] });
      break;
    }
    case "meeting-created":
      void queryClient.invalidateQueries({ queryKey: ["meetings"] });
      break;
  }
}
```

- [ ] **Step 4.2: Unit test the matrix**

`src/shared/api/__tests__/invalidations.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { invalidateAfter } from "../invalidations";

function spyClient() {
  const qc = new QueryClient();
  const spy = vi.spyOn(qc, "invalidateQueries").mockResolvedValue(undefined as never);
  return { qc, keys: () => spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey)) };
}

describe("invalidateAfter", () => {
  it("transcript-edited invalidates the full SPEC §6 rule-4 fan-out incl. meeting", () => {
    const { qc, keys } = spyClient();
    invalidateAfter({ type: "transcript-edited", meetingId: "m1" }, qc);
    expect(keys()).toEqual([
      '["transcript","m1"]', '["meeting","m1"]', '["minutes","m1"]',
      '["action-items","m1"]', '["decisions","m1"]', '["risks","m1"]', '["rag"]',
    ]);
  });

  it("speaker-confirmed invalidates transcript + meeting + rag (rule 5)", () => {
    const { qc, keys } = spyClient();
    invalidateAfter({ type: "speaker-confirmed", meetingId: "m1" }, qc);
    expect(keys()).toEqual(['["transcript","m1"]', '["meeting","m1"]', '["rag"]']);
  });

  it("minutes-regenerated invalidates minutes + meeting; meeting-created invalidates list", () => {
    const { qc, keys } = spyClient();
    invalidateAfter({ type: "minutes-regenerated", meetingId: "m1" }, qc);
    invalidateAfter({ type: "meeting-created" }, qc);
    expect(keys()).toEqual(['["minutes","m1"]', '["meeting","m1"]', '["meetings"]']);
  });
});
```

- [ ] **Step 4.3: Wire `useUpdateSegment` (I4 + I5 producer side) — including the VERSION_CONFLICT branch**

`src/features/transcript/queries.ts`:

```ts
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getLatestMeetingTask, getTranscript, updateSegment } from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import { useApiMutation } from "@shared/api/useApiMutation";
import { invalidateAfter } from "@shared/api/invalidations";

// useTranscriptQuery / useLatestMeetingTaskQuery unchanged.

export function useUpdateSegment(meetingId: string) {
  const qc = useQueryClient();
  return useApiMutation<
    { segmentId: string; transcriptVersion: number; editStatus: string; downstreamStaleMarked: boolean },
    { segmentId: string; text: string; version: number; reason: string | null }
  >({
    actionKey: "edit-segment",
    mutationFn: (input, key) => updateSegment(meetingId, input.segmentId, input.text, input.version, input.reason, key),
    onSuccess: () => invalidateAfter({ type: "transcript-edited", meetingId }, qc),
    onError: (error: ApiClientError) => {
      if (error.code === "VERSION_CONFLICT") {
        // Make the "已自动刷新到最新版本" banner TRUE: refetch the
        // authoritative version before the user re-edits.
        void qc.invalidateQueries({ queryKey: ["transcript", meetingId] });
        void qc.invalidateQueries({ queryKey: ["meeting", meetingId] });
      }
    },
  });
}
```

(`updateSegment` already accepts the trailing key after Task 3.)

- [ ] **Step 4.4: Wire `useRegenerateMinutes` (I5)**

`src/features/minutes/queries.ts`:

```ts
export function useRegenerateMinutes(meetingId: string) {
  const qc = useQueryClient();
  return useApiMutation<MinutesData, { transcriptVersion: number; minutesVersion: number }>({
    actionKey: "regen-minutes",
    mutationFn: (input, key) => regenerateMinutes(meetingId, input.transcriptVersion, input.minutesVersion, key),
    onSuccess: (data) => {
      qc.setQueryData(["minutes", meetingId], data);
      invalidateAfter({ type: "minutes-regenerated", meetingId }, qc);
    },
  });
}
```

(`MinutesPage` keeps reading `meeting.transcriptVersion` from `["meeting", meetingId]` — now correct because Step 4.3 invalidates that key on every edit and this hook refreshes it after regenerate. Update `MinutesPage`'s `regenErrMsg` to prefer `regen.errorMessage`.)

- [ ] **Step 4.5: Wire speaker confirm/reject (I6)**

`src/features/speakers/MeetingSpeakerConfirmPage.tsx` — add `useQueryClient` and call the helper after both mutations succeed:

```tsx
import { useQueryClient } from "@tanstack/react-query";
import { invalidateAfter } from "@shared/api/invalidations";
// inside the component:
const queryClient = useQueryClient();
// in handleConfirm, after `await confirmMeetingSpeaker(...)`:
invalidateAfter({ type: "speaker-confirmed", meetingId }, queryClient);
await reload();
// in handleReject, after `await rejectMeetingSpeaker(...)`:
invalidateAfter({ type: "speaker-confirmed", meetingId }, queryClient);
await reload();
```

- [ ] **Step 4.6: Switch `MeetingCreatePage` to the hook (I12)**

`src/features/meetings/queries.ts`:

```ts
export function useCreateMeeting() {
  const qc = useQueryClient();
  return useApiMutation<Meeting, import("@shared/api/types").CreateMeetingRequest>({
    actionKey: "create-meeting",
    mutationFn: (data, key) => apiCreateMeeting(data, key),
    onSuccess: () => invalidateAfter({ type: "meeting-created" }, qc),
  });
}
```

`src/features/meetings/MeetingCreatePage.tsx` — drop the direct `createMeeting` import and the `submitting`/`error` state in favor of the hook:

```tsx
const create = useCreateMeeting();
async function onSubmit(event: FormEvent<HTMLFormElement>) {
  event.preventDefault();
  const participants = /* unchanged mapping */;
  try {
    const meeting = await create.mutateAsync({ title: title.trim(), language, participants });
    navigate(`/meetings/${meeting.meetingId}`);
  } catch {
    /* rendered via create.errorMessage */
  }
}
// render: {create.errorMessage ? <div className="error" role="alert">{create.errorMessage}</div> : null}
// button: disabled={create.isPending || !title.trim()}
```

- [ ] **Step 4.7: Integration test — conflict really refetches (I4)**

Add to `src/features/transcript/__tests__/TranscriptPage.test.tsx`:

```tsx
it("VERSION_CONFLICT triggers a transcript refetch so the banner is true (I4)", async () => {
  let transcriptFetches = 0;
  server.use(
    http.get("/api/meetings/:meetingId/transcript", ({ params }) => {
      transcriptFetches += 1;
      return HttpResponse.json({ success: true, data: { ...transcriptFixture, meetingId: String(params.meetingId), transcriptVersion: transcriptFetches }, error: null, requestId: "r", traceId: "t" });
    }),
    http.patch("/api/meetings/:meetingId/transcript/segments/:segmentId", () =>
      HttpResponse.json(
        { success: false, data: null, error: { code: "VERSION_CONFLICT", message: "stale", retryable: false, details: {} }, requestId: "r", traceId: "t" },
        { status: 409 },
      ),
    ),
  );
  renderTranscriptPage(); // existing helper in this file
  fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
  fireEvent.click(screen.getByRole("button", { name: "保存" })); // unchanged text guard: first change the textarea value
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("已自动刷新"));
  await waitFor(() => expect(transcriptFetches).toBeGreaterThanOrEqual(2));
});
```

(Adapt to the file's existing fixture/render helpers; the edit must change the textarea first or `saveEdit` short-circuits — mirror the file's existing edit test.)

- [ ] **Step 4.8: Run, type-check, commit**

```bash
npx vitest run src/shared/api/__tests__/invalidations.test.ts src/features/transcript/__tests__/TranscriptPage.test.tsx src/features/minutes/__tests__/MinutesPage.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): I4+I5+I6+I12 central invalidateAfter matrix; VERSION_CONFLICT refetch; speaker-confirm + meeting-create cache wiring"
```

---

## Task 5: C1 — enrollment rewrite: tenant-scoped `/files` upload, no carrier meeting

`SpeakerEnrollPanel.tsx:22-31` grabs `meetingsList.items[0]` (or creates a junk meeting) and pushes enrollment audio through the **meeting-scoped** audio upload; `AudioUploadApplicationService.java:242` (verified) then unconditionally `createForCompletedAudioUpload(...)` → full GPU pipeline run against someone's real meeting. Per D1, mirror the proven BFF orchestration (`enrollment.py:170-310`): `POST /api/files` → `POST /api/files/{uploadId}/parts` → PUT signed URL → `POST /api/files/{uploadId}/complete` → `POST /api/speaker-profiles/{profileId}/enrollments`. Delete `getOrCreateSystemMeeting` entirely. MIME strategy per Verification note 2 (BFF parity; no Java change needed). Folds in the panel's Minor findings (objectURL leak, ETag preference, poll cap).

**Files:**
- Modify: `packages/meeting-contracts/openapi/public-api.yaml` (description only)
- Modify: `src/shared/api/client.ts` (three `/files` functions)
- Modify: `src/shared/api/mocks/handlers.ts` (`/api/files` trio + signed PUT)
- Rewrite: `src/features/speakers/SpeakerEnrollPanel.tsx`
- Rewrite: `src/features/speakers/__tests__/SpeakerEnrollPanel.test.tsx`

- [ ] **Step 5.1: Contracts — amend the stale `/files` description**

In `packages/meeting-contracts/openapi/public-api.yaml:328` change:

```yaml
      description: Initialize a tenant-scoped multipart upload for reference documents and speaker-enrollment audio samples. Meeting audio that should enter the processing pipeline must use the meeting-scoped audio upload endpoints.
```

Then validate and regenerate (descriptions propagate into generated TS docs, so codegen drift must be committed):

```bash
cd packages/meeting-contracts
npm run check
npm run codegen
git add -A && git commit -m "fix(contracts): /files description covers speaker-enrollment audio samples (BFF + web both ship enrollment audio through /files)"
```

- [ ] **Step 5.2: Add `/files` client functions**

In `src/shared/api/client.ts` (types mirror `FileUploadSession`/`FileUploadPart`/`FileUploadCompleteResponse` from the contract):

```ts
// ── Generic tenant-scoped files (enrollment audio, reference docs) ─

export interface FileUploadPartSigned {
  partNumber: number;
  partSha256: string;
  sizeBytes: number;
  etag?: string | null;
  uploadUrl: string;
  expiresAt: string;
  headers: Record<string, string>;
}

export interface FileUploadSessionT {
  uploadId: string;
  expiresAt: string;
  partSizeBytes: number;
  maxPartCount: number;
  contentType: string;
  fileName: string;
  fileSizeBytes: number;
  fileSha256: string;
  fileId?: string | null;
  parts: FileUploadPartSigned[];
}

export interface FileUploadCompleteT {
  fileId: string;
  sha256: string;
  sizeBytes: number;
  contentType: string;
}

export async function createFileUpload(
  data: { fileName: string; contentType: string; fileSizeBytes: number; fileSha256: string },
  idempotencyKey?: string,
) {
  return request<FileUploadSessionT>("POST", "/files", data, idempotencyKey ?? generateIdempotencyKey("create-file-upload"));
}

export async function createFileUploadPart(
  uploadId: string,
  data: { partNumber: number; sizeBytes: number; partSha256: string },
  idempotencyKey?: string,
) {
  return request<FileUploadPartSigned>(
    "POST",
    `/files/${uploadId}/parts`,
    data,
    idempotencyKey ?? generateIdempotencyKey(`file-part-${data.partNumber}`),
  );
}

export async function completeFileUpload(
  uploadId: string,
  data: { fileSha256: string; parts: Array<{ partNumber: number; partSha256: string; etag: string }> },
  idempotencyKey?: string,
) {
  return request<FileUploadCompleteT>(
    "POST",
    `/files/${uploadId}/complete`,
    data,
    idempotencyKey ?? generateIdempotencyKey("complete-file-upload"),
  );
}
// Binary PUT to the signed URL reuses the existing putAudioUploadPart/uploadBinary.
```

- [ ] **Step 5.3: MSW handlers for the new endpoints**

Append to `src/shared/api/mocks/handlers.ts`:

```ts
  // ── Generic /files upload (enrollment audio) ──────────────────────
  http.post("/api/files", async ({ request }) => {
    const body = (await request.json()) as { fileName: string; contentType: string; fileSizeBytes: number; fileSha256: string };
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        uploadId: "fupl_01",
        expiresAt: "2026-06-12T10:00:00Z",
        partSizeBytes: Math.max(body.fileSizeBytes, 5 * 1024 * 1024),
        maxPartCount: 10000,
        contentType: body.contentType,
        fileName: body.fileName,
        fileSizeBytes: body.fileSizeBytes,
        fileSha256: body.fileSha256,
        fileId: null,
        parts: [],
      },
      error: null, requestId: "r", traceId: "t",
    });
  }),

  http.post("/api/files/:uploadId/parts", async ({ request }) => {
    const body = (await request.json()) as { partNumber: number; sizeBytes: number; partSha256: string };
    return HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: {
        partNumber: body.partNumber,
        partSha256: body.partSha256,
        sizeBytes: body.sizeBytes,
        etag: null,
        uploadUrl: `http://localhost/upload/file/${body.partNumber}`,
        expiresAt: "2026-06-12T10:15:00Z",
        headers: { "Content-Type": "audio/wav" },
      },
      error: null, requestId: "r", traceId: "t",
    });
  }),

  http.put("http://localhost/upload/file/:partNumber", () =>
    new HttpResponse(null, { status: 200, headers: { ETag: '"etag_file_1"' } }),
  ),

  http.post("/api/files/:uploadId/complete", () =>
    HttpResponse.json<ApiResponse<unknown>>({
      success: true,
      data: { fileId: "file_generic_01", sha256: "a".repeat(64), sizeBytes: 12, contentType: "audio/wav" },
      error: null, requestId: "r", traceId: "t",
    }),
  ),
```

- [ ] **Step 5.4: Write the failing rewrite test**

Rewrite `src/features/speakers/__tests__/SpeakerEnrollPanel.test.tsx` — keep the two existing inline-announce tests (same UX strings) and add the path-isolation test:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { SpeakerEnrollPanel } from "../SpeakerEnrollPanel";

// renderPanel / mockEnrollmentStatus / submitUpload helpers stay as in the
// current file; submitUpload's File stays { type: "audio/wav" }.

describe("SpeakerEnrollPanel (C1 rewrite)", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    server.events.removeAllListeners();
  });

  it("uploads via tenant-scoped /files and NEVER touches any /api/meetings endpoint", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const touched: string[] = [];
    server.events.on("request:start", ({ request }) => {
      touched.push(`${request.method} ${new URL(request.url).pathname}`);
    });
    let enrollmentBody: { audioFileId?: string } = {};
    server.use(
      http.post("/api/speaker-profiles/:profileId/enrollments", async ({ request }) => {
        enrollmentBody = (await request.json()) as { audioFileId?: string };
        return HttpResponse.json({
          success: true,
          data: { enrollmentId: "spe_new", speakerProfileId: "spk_test", tenantId: "t", sourceAudioFileId: "file_generic_01", enrollmentStatus: "PENDING", qualityScore: null, modelVersion: null, errorCode: null, createdAt: "2026-06-12T00:00:00Z", updatedAt: "2026-06-12T00:00:00Z" },
          error: null, requestId: "r", traceId: "t",
        });
      }),
    );
    mockEnrollmentStatus("SUCCEEDED");
    renderPanel();

    await submitUpload();

    expect(touched.filter((p) => p.includes("/api/meetings"))).toEqual([]);
    expect(touched).toEqual(expect.arrayContaining([
      "POST /api/files",
      "POST /api/files/fupl_01/parts",
      "POST /api/files/fupl_01/complete",
      "POST /api/speaker-profiles/spk_test/enrollments",
    ]));
    expect(enrollmentBody.audioFileId).toBe("file_generic_01");
  });

  // …the two pre-existing inline-announce tests, unchanged assertions…

  it("stops polling and reports an error after the timeout cap", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(
      http.get("/api/speaker-profiles/:profileId/enrollments", () =>
        HttpResponse.json({ success: true, data: { items: [{ enrollmentId: "spe_new", speakerProfileId: "spk_test", tenantId: "t", sourceAudioFileId: "f", enrollmentStatus: "PENDING", qualityScore: null, modelVersion: null, errorCode: null, createdAt: "x", updatedAt: "x" }] }, error: null, requestId: "r", traceId: "t" }),
      ),
    );
    renderPanel();
    await submitUpload();
    await act(async () => { await vi.advanceTimersByTimeAsync(121_000); });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("处理超时"));
  });
});
```

Run: `npx vitest run src/features/speakers/__tests__/SpeakerEnrollPanel.test.tsx` — expected failures: requests still hit `/api/meetings` and `/api/meetings/:id/files/audio/uploads`; timeout test fails (polling never stops today).

- [ ] **Step 5.5: Rewrite the panel**

`src/features/speakers/SpeakerEnrollPanel.tsx` — delete `getOrCreateSystemMeeting` and the `createMeeting`/`listMeetings`/`createAudioUpload`/`createAudioUploadPart`/`completeAudioUpload` imports. New imports and core logic:

```tsx
import { useEffect, useMemo, useRef, useState } from "react";
import {
  completeFileUpload,
  createFileUpload,
  createFileUploadPart,
  createSpeakerEnrollment,
  listSpeakerEnrollments,
  putAudioUploadPart,
} from "@shared/api/client";
import { sha256Hex } from "@shared/utils/sha256-stream";

const POLL_INTERVAL_MS = 1500;
const POLL_TIMEOUT_MS = 120_000;

// The /files contract only accepts these four MIME values
// (CreateFileUploadRequest enum). Browser recordings are audio/webm, so —
// exactly like the production ai-worker BFF (enrollment.py) — anything
// outside the allowlist ships under the audio/wav label; the worker sniffs
// the real container, not the label.
const FILES_MIME_ALLOWLIST = new Set(["audio/wav", "audio/mpeg", "audio/x-m4a", "audio/flac"]);

function resolveUploadMeta(blob: Blob | File, profileId: string): { fileName: string; contentType: string } {
  if (blob instanceof File && FILES_MIME_ALLOWLIST.has(blob.type)) {
    return { fileName: blob.name, contentType: blob.type };
  }
  return { fileName: `voice_enroll_${profileId}_${Date.now()}.wav`, contentType: "audio/wav" };
}
```

`handleEnroll` (replacing lines 141-211; status strings unchanged so existing tests keep matching):

```tsx
  const handleEnroll = async () => {
    setError(null);
    setEnrollmentFeedback(null);
    setEnrolling(true);
    setStatusText("准备上传通道…");
    try {
      const fileBlob: Blob | File | null = tab === "record" ? recordedBlob : uploadFile;
      if (!fileBlob) throw new Error(tab === "record" ? "请先录音" : "请先选择音频文件");
      const { fileName, contentType } = resolveUploadMeta(fileBlob, profileId);

      setStatusText("正在计算音频指纹…");
      const sha256 = await sha256Hex(fileBlob);

      setStatusText("正在申请上传通道…");
      // No partSizeBytes: the server clamps it to ≥ fileSize, so this is
      // always a single-part upload (and the old `size * 2` could violate
      // the 1 MiB contract minimum for short clips).
      const session = await createFileUpload({
        fileName,
        contentType,
        fileSizeBytes: fileBlob.size,
        fileSha256: sha256,
      });

      setStatusText("正在上传录音数据…");
      const signed = await createFileUploadPart(session.uploadId, {
        partNumber: 1,
        sizeBytes: fileBlob.size,
        partSha256: sha256,
      });
      const uploaded = await putAudioUploadPart(signed.uploadUrl, fileBlob, signed.headers);

      setStatusText("正在校验并完成上传…");
      const completed = await completeFileUpload(session.uploadId, {
        fileSha256: sha256,
        // Prefer the storage PUT's real ETag; signed.etag only exists on
        // re-requested parts.
        parts: [{ partNumber: 1, partSha256: sha256, etag: uploaded.etag || signed.etag || "etag_1" }],
      });

      setStatusText("正在提交声纹注册任务…");
      const enrollment = await createSpeakerEnrollment(profileId, completed.fileId);
      if (!enrollment?.enrollmentId) throw new Error("声纹档案注册返回数据异常");

      pollStartedAtRef.current = Date.now();
      setPollingEnrollmentId(enrollment.enrollmentId);
      setRecordedBlob(null);
      setUploadFile(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setEnrolling(false);
      setStatusText(null);
    }
  };
```

Polling effect gains the timeout cap (Minor fold-in):

```tsx
  const pollStartedAtRef = useRef(0);

  useEffect(() => {
    if (!pollingEnrollmentId) return;
    const poll = async () => {
      if (Date.now() - pollStartedAtRef.current > POLL_TIMEOUT_MS) {
        setEnrollmentFeedback({ tone: "error", message: "声纹处理超时，请稍后在列表中查看结果或重试" });
        setPollingEnrollmentId(null);
        onEnrollSuccess();
        return;
      }
      try {
        const resp = await listSpeakerEnrollments(profileId);
        const match = resp.items.find((e) => e.enrollmentId === pollingEnrollmentId);
        /* …existing SUCCEEDED / FAILED branches unchanged… */
      } catch { /* polling will retry */ }
    };
    const timer = setInterval(poll, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [pollingEnrollmentId, profileId, onEnrollSuccess]);
```

Object-URL leak fix (Minor fold-in) — replace the inline `URL.createObjectURL(recordedBlob)` at the old line 302:

```tsx
  const previewUrl = useMemo(
    () => (recordedBlob ? URL.createObjectURL(recordedBlob) : null),
    [recordedBlob],
  );
  useEffect(() => () => { if (previewUrl) URL.revokeObjectURL(previewUrl); }, [previewUrl]);
  // …in JSX: {previewUrl && !recording ? <audio src={previewUrl} controls … /> : null}
```

- [ ] **Step 5.6: Run the rewritten suite, full suite, type check**

```bash
npx vitest run src/features/speakers/__tests__/SpeakerEnrollPanel.test.tsx   # all green
npm test
npx tsc --noEmit
```

- [ ] **Step 5.7: Commit**

```bash
git add -A && git commit -m "fix(web): C1 enrollment uploads via tenant-scoped /files (BFF-parity); delete carrier-meeting hack; poll cap + objectURL/etag fixes"
```

---

## Task 6: D5 — SSE hardening: live Last-Event-Id, backoff, authenticated export stream *(closes I7, I13)*

**Mapping:** I7 (`subscribeTaskEvents` snapshots `handlers.lastEventId` once — `useTaskEventsStream.ts:100-101` passes `null` at subscribe — and burns its 3 attempts with zero delay), I13 (`ExportsPage.tsx:113-138` bare `new EventSource` can never send `Authorization` and is keyed on `jobs` identity, churning every 3s poll).

**Files:**
- Modify: `src/shared/api/client.ts`
- Modify: `src/shared/queries/useTaskEventsStream.ts`
- Modify: `src/features/exports/ExportsPage.tsx`
- Create: `src/shared/api/__tests__/client.sse.test.ts`
- Modify: `src/features/exports/__tests__/ExportsPage.sse.test.tsx`

- [ ] **Step 6.1: Refactor `subscribeTaskEvents` in `client.ts`**

```ts
export const SSE_BACKOFF_SCHEDULE_MS = [1_000, 2_000, 4_000] as const;
export const SSE_BACKOFF_CAP_MS = 10_000;

export function sseBackoffMs(failureCount: number): number {
  // 1-indexed failure count → 1s / 2s / 4s, capped at 10s.
  const idx = Math.min(Math.max(failureCount, 1), SSE_BACKOFF_SCHEDULE_MS.length) - 1;
  return Math.min(SSE_BACKOFF_SCHEDULE_MS[idx]!, SSE_BACKOFF_CAP_MS);
}

function abortableDelay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

interface SseMessage { event: string | null; data: string }

/** Shared fetch-based SSE reader: parses `event:`/`data:` frames. */
async function readSseStream(
  path: string,
  signal: AbortSignal,
  lastEventId: string | null,
  onMessage: (message: SseMessage) => void,
): Promise<void> {
  const headers: Record<string, string> = {
    Accept: "text/event-stream",
    "X-Request-Id": generateId("req"),
    "X-Trace-Id": generateId("trace"),
  };
  if (authToken) headers.Authorization = `Bearer ${authToken}`;
  if (lastEventId) headers["Last-Event-Id"] = lastEventId;

  const response = await fetch(`${API_BASE}${path}`, { headers, signal });
  if (!response.ok || !response.body) throw new Error(`SSE ${response.status}`);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split("\n\n");
    buffer = chunks.pop() ?? "";
    for (const chunk of chunks) {
      const lines = chunk.split("\n");
      const event = lines.find((l) => l.startsWith("event:"))?.slice(6).trim() ?? null;
      const data = lines.filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trimStart()).join("\n");
      if (data) onMessage({ event, data });
    }
  }
}

export function subscribeTaskEvents(
  taskId: string,
  handlers: {
    /** Read live at every (re)connect — never snapshotted (review I7). */
    getLastEventId?: () => string | null;
    onEvent: (event: TaskEvent) => void;
    onFallback: () => void;
  },
): TaskEventSubscription {
  const controller = new AbortController();
  let failures = 0;

  const connect = async () => {
    while (!controller.signal.aborted && failures < 3) {
      try {
        await readSseStream(
          `/processing-tasks/${taskId}/events`,
          controller.signal,
          handlers.getLastEventId?.() ?? null,
          ({ data }) => handlers.onEvent(JSON.parse(data) as TaskEvent),
        );
        failures = 0; // clean server close → immediate reconnect attempt
      } catch {
        if (controller.signal.aborted) return;
        failures += 1;
        if (failures < 3) await abortableDelay(sseBackoffMs(failures), controller.signal);
      }
    }
    if (!controller.signal.aborted) handlers.onFallback();
  };

  void connect();
  return { close: () => controller.abort() };
}

export function subscribeExportEvents(
  exportId: string,
  handlers: { onEvent: (eventName: string | null) => void },
): TaskEventSubscription {
  const controller = new AbortController();
  let failures = 0;
  const connect = async () => {
    while (!controller.signal.aborted && failures < 3) {
      try {
        await readSseStream(
          `/exports/${encodeURIComponent(exportId)}/events`,
          controller.signal,
          null,
          ({ event }) => handlers.onEvent(event),
        );
        failures = 0;
      } catch {
        if (controller.signal.aborted) return;
        failures += 1;
        if (failures < 3) await abortableDelay(sseBackoffMs(failures), controller.signal);
      }
    }
    // No onFallback: ExportsPage's 3s polling is the standing fallback.
  };
  void connect();
  return { close: () => controller.abort() };
}
```

(Existing behavior preserved: clean stream close resets `failures` and reconnects; only *errors* count toward the 3-strike fallback — now with backoff so a server restart no longer burns all strikes in milliseconds.)

- [ ] **Step 6.2: Pass a live getter from the hook**

`src/shared/queries/useTaskEventsStream.ts:100-101`:

```ts
    subRef.current = subscribeTaskEvents(taskId, {
      getLastEventId: () => lastEventId.current,
      onEvent: (event) => { /* unchanged */ },
      onFallback: () => setConnectionMode("POLLING"),
    });
```

- [ ] **Step 6.3: Replace the bare EventSource in `ExportsPage`**

Replace the `useEffect` at `ExportsPage.tsx:113-138`:

```tsx
  // SSE live-nudge through the authenticated fetch-based helper (the bare
  // EventSource could never send Authorization, so it silently failed).
  // Keyed on the SORTED ids of active jobs — not array identity — so the 3s
  // poll no longer tears the streams down and reopens them on every tick.
  const activeIdsKey = useMemo(
    () => jobs.filter((j) => !TERMINAL_STATUSES.has(j.status)).map((j) => j.exportId).sort().join("|"),
    [jobs],
  );
  useEffect(() => {
    if (!activeIdsKey) return;
    const subs = activeIdsKey.split("|").map((exportId) =>
      subscribeExportEvents(exportId, { onEvent: () => void loadAll() }),
    );
    return () => { for (const sub of subs) sub.close(); };
  }, [activeIdsKey, loadAll]);
```

(Import `subscribeExportEvents` from `@shared/api/client`; drop the `window.EventSource` feature check.)

- [ ] **Step 6.4: Tests**

Create `src/shared/api/__tests__/client.sse.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { sseBackoffMs, subscribeTaskEvents } from "@shared/api/client";

describe("SSE hardening (I7)", () => {
  it("backoff schedule is 1s/2s/4s capped at 10s", () => {
    expect(sseBackoffMs(1)).toBe(1_000);
    expect(sseBackoffMs(2)).toBe(2_000);
    expect(sseBackoffMs(3)).toBe(4_000);
    expect(sseBackoffMs(99)).toBe(4_000); // schedule end, still ≤ cap
  });

  it("reads Last-Event-Id live via the getter on each connect", async () => {
    const seenIds: Array<string | null> = [];
    let connects = 0;
    server.use(
      http.get("/api/processing-tasks/:taskId/events", ({ request }) => {
        connects += 1;
        seenIds.push(request.headers.get("Last-Event-Id"));
        // One event then clean close → client reconnects immediately.
        const body = 'data: {"eventId":"evt_7","sequenceNo":7,"eventType":"TASK_HEARTBEAT","taskId":"task_01","status":"RUNNING","emittedAt":"2026-06-12T00:00:00Z"}\n\n';
        return new HttpResponse(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
      }),
    );
    let cursor: string | null = null;
    const sub = subscribeTaskEvents("task_01", {
      getLastEventId: () => cursor,
      onEvent: (event) => { cursor = event.eventId; },
      onFallback: () => undefined,
    });
    await new Promise((r) => setTimeout(r, 50)); // allow ≥2 connects
    sub.close();
    expect(connects).toBeGreaterThanOrEqual(2);
    expect(seenIds[0]).toBeNull();
    expect(seenIds[1]).toBe("evt_7"); // resumed from the live cursor, not a snapshot
  });
});
```

Update `src/features/exports/__tests__/ExportsPage.sse.test.tsx`: replace any `EventSource` stubbing with an MSW handler for `GET /api/exports/:exportId/events` that captures `request.headers.get("Authorization")`; assert it equals the bearer token set via `setAuthToken("mock-access-token")` in the test, and that a captured `event: EXPORT_STATUS_CHANGED` frame triggers a `loadAll` refetch (jobs table updates).

- [ ] **Step 6.5: Run, type-check, commit**

```bash
npx vitest run src/shared/api/__tests__/client.sse.test.ts src/features/exports/__tests__/ExportsPage.sse.test.tsx src/features/tasks/__tests__/TaskProgressPage.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): I7+I13 SSE live Last-Event-Id getter + reconnect backoff; exports stream via authenticated fetch helper keyed on sorted active ids"
```

---

## Task 7: D8 — upload flow: real cancel, stable offsets, safe finalize *(closes I8, I9, I10)*

**Mapping:** I8 (abort doesn't abort in-flight PUTs; `part-start` flips `aborted/failed` back to `uploading` — `upload-reducer.ts:81-90`), I9 (`AudioUploadPage.tsx:123` computes offsets from a stale `state.session` closure), I10 (`finalize`'s unwrapped `getLatestMeetingTask` at lines 199-200 can flip a completed upload to failed).

**Files:**
- Modify: `src/features/audio/upload-reducer.ts`
- Modify: `src/features/audio/AudioUploadPage.tsx`
- Modify: `src/shared/api/client.ts` (`uploadBinary`/`putAudioUploadPart` accept `AbortSignal`)
- Modify: `src/features/audio/__tests__/upload-reducer.test.ts`, `src/features/audio/__tests__/AudioUploadPage.test.tsx`

- [ ] **Step 7.1: Reducer terminal-state guards (I8)**

In `src/features/audio/upload-reducer.ts`:

```ts
const TERMINAL_UI_STATUSES: ReadonlySet<UploadUiStatus> = new Set([
  "aborted", "expired", "completed", "failed",
]);
```

```ts
    case "part-start":
      // Late worker dispatches must not resurrect a terminal upload
      // (review I8: in-flight parts flipped "aborted"/"failed" back to
      // "uploading"). "failed" exits only via explicit prepare/resume.
      if (TERMINAL_UI_STATUSES.has(state.status)) return state;
      return { /* …existing body unchanged… */ };
    case "part-complete": {
      if (TERMINAL_UI_STATUSES.has(state.status)) return state;
      /* …existing body unchanged… */
    }
```

Tests in `__tests__/upload-reducer.test.ts`:

```ts
it("part-start after aborted is a no-op (I8)", () => {
  const aborted = uploadReducer({ ...stateWithParts, status: "aborted" }, { type: "aborted" });
  const next = uploadReducer(aborted, { type: "part-start", partNumber: 1, attempts: 2 });
  expect(next.status).toBe("aborted");
  expect(next).toBe(aborted); // exact same state object
});

it("part-complete after failed keeps failed status and errorCode (I8)", () => {
  const failed = uploadReducer(stateWithParts, { type: "part-failed", partNumber: 2, errorCode: "OSS_WRITE_FAILED" });
  const next = uploadReducer(failed, { type: "part-complete", partNumber: 1, etag: "e1" });
  expect(next.status).toBe("failed");
  expect(next.errorCode).toBe("OSS_WRITE_FAILED");
});
```

(`stateWithParts` = build via `session` action with two pending parts, following the file's existing fixtures.)

- [ ] **Step 7.2: Signal-aware binary PUT**

`client.ts`:

```ts
async function uploadBinary(
  url: string,
  body: Blob,
  headers: Record<string, string>,
  signal?: AbortSignal,
): Promise<{ etag: string }> {
  // …unchanged except: fetch(url, { method: "PUT", headers, body, signal });
}

export async function putAudioUploadPart(
  uploadUrl: string,
  part: Blob,
  headers: Record<string, string>,
  signal?: AbortSignal,
) {
  return uploadBinary(uploadUrl, part, headers, signal);
}
```

- [ ] **Step 7.3: Thread the AbortController + explicit partSizeBytes + safe finalize in `AudioUploadPage.tsx`**

```tsx
  const abortControllerRef = useRef<AbortController | null>(null);

  async function startUpload() {
    if (!file || !meetingId) return;
    const controller = new AbortController();
    abortControllerRef.current = controller;
    dispatch({ type: "prepare" });
    setMessage(null);
    try {
      validateFile(file);
      const sha256 = await sha256Hex(file);
      setFileSha256(sha256);
      const session = await createAudioUpload(meetingId, { /* unchanged */ });
      window.localStorage.setItem(storageKey, session.uploadId);
      const parts = await buildParts(file, session.partSizeBytes, sha256);
      dispatch({ type: "session", session, parts });
      await uploadParts(session.uploadId, parts, session.partSizeBytes, controller.signal); // I9: server value, not stale closure
      await finalize(session.uploadId, sha256, parts);
    } catch (cause) {
      if (controller.signal.aborted) return; // user abort already drove the reducer (I8)
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause));
      dispatch({ type: "failed", errorCode: apiError.code || "INTERNAL_ERROR" });
    }
  }

  async function uploadParts(uploadId: string, parts: UploadPartState[], partSizeBytes: number, signal: AbortSignal) {
    let nextIndex = 0;
    async function worker() {
      while (nextIndex < parts.length && !signal.aborted) {
        const part = parts[nextIndex];
        nextIndex += 1;
        if (!part) return;
        await uploadOnePart(uploadId, part, partSizeBytes, signal);
      }
    }
    await Promise.all(Array.from({ length: Math.min(concurrency, parts.length) }, () => worker()));
  }

  async function uploadOnePart(uploadId: string, part: UploadPartState, partSizeBytes: number, signal: AbortSignal) {
    if (!file) return;
    for (let attempt = 1; attempt <= MAX_PART_RETRIES; attempt += 1) {
      if (signal.aborted) throw new DOMException("upload aborted", "AbortError");
      dispatch({ type: "part-start", partNumber: part.partNumber, attempts: attempt });
      try {
        const signed = await createAudioUploadPart(meetingId, uploadId, { /* unchanged */ });
        const offset = (part.partNumber - 1) * partSizeBytes; // I9: explicit param
        const blob = file.slice(offset, offset + part.sizeBytes);
        const uploaded = await putAudioUploadPart(signed.uploadUrl, blob, signed.headers, signal);
        /* unchanged etag/complete dispatch */
        return;
      } catch (cause) {
        if (signal.aborted) throw cause; // don't dispatch part-failed for user cancel
        if (attempt === MAX_PART_RETRIES) {
          const apiError = cause as ApiClientError;
          dispatch({ type: "part-failed", partNumber: part.partNumber, errorCode: apiError.code || "OSS_WRITE_FAILED" });
          throw cause;
        }
      }
    }
  }

  async function abort() {
    if (!meetingId || !state.session) return;
    abortControllerRef.current?.abort();   // stop in-flight PUTs + worker loops first
    dispatch({ type: "aborted" });          // terminal in the UI immediately (reducer guards hold it)
    try {
      await abortAudioUpload(meetingId, state.session.uploadId);
      window.localStorage.removeItem(storageKey);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setMessage(apiError.message || String(cause)); // stay "aborted"; never dispatch failed here
    }
  }

  async function finalize(uploadId: string, sha256: string, parts: UploadPartState[]) {
    dispatch({ type: "complete-start" });
    const completed = await completeAudioUpload(meetingId, uploadId, { /* unchanged */ });
    dispatch({ type: "completed", session: completed });
    window.localStorage.removeItem(storageKey);
    try {
      const task = await getLatestMeetingTask(meetingId);
      navigate(`/meetings/${meetingId}/tasks/${task.taskId}`);
    } catch {
      // I10: the upload IS complete — a flaky latest-task lookup must not
      // repaint it as failed. Leave the completed state + a hint.
      setMessage("上传已完成。获取处理任务失败，请从会议详情页打开任务进度。");
    }
  }
```

`resume()` gets the same treatment: create a controller, pass `state.session.partSizeBytes` and the signal into `uploadParts`, and skip the `failed` dispatch when `signal.aborted`.

- [ ] **Step 7.4: Page-level tests**

Add to `src/features/audio/__tests__/AudioUploadPage.test.tsx` (reuse the file's render/file-pick helpers):

```tsx
it("cancel keeps the reducer in aborted even while parts are in flight (I8)", async () => {
  server.use(
    http.put("http://localhost/upload/part/:partNumber", async () => {
      await new Promise((r) => setTimeout(r, 200)); // slow PUT so cancel races it
      return new HttpResponse(null, { status: 200, headers: { ETag: "etag_slow" } });
    }),
  );
  renderUploadPage();
  await pickFile();
  fireEvent.click(screen.getByRole("button", { name: "开始上传" }));
  fireEvent.click(await screen.findByRole("button", { name: "取消上传" }));
  await waitFor(() => expect(screen.getByText("aborted")).toBeInTheDocument());
  await new Promise((r) => setTimeout(r, 300)); // let the slow PUT settle
  expect(screen.getByText("aborted")).toBeInTheDocument(); // never flipped back
});

it("a transient latest-task failure does not repaint a completed upload as failed (I10)", async () => {
  server.use(
    http.get("/api/meetings/:meetingId/processing-tasks/latest", () =>
      HttpResponse.json(
        { success: false, data: null, error: { code: "DEPENDENCY_UNAVAILABLE", message: "down", retryable: true, details: {} }, requestId: "r", traceId: "t" },
        { status: 503 },
      ),
    ),
  );
  renderUploadPage();
  await pickFile();
  fireEvent.click(screen.getByRole("button", { name: "开始上传" }));
  await waitFor(() => expect(screen.getByText("completed")).toBeInTheDocument());
  expect(screen.getByText(/上传已完成/)).toBeInTheDocument();
  expect(screen.queryByText("failed")).not.toBeInTheDocument();
});
```

- [ ] **Step 7.5: Run, type-check, commit**

```bash
npx vitest run src/features/audio/__tests__/upload-reducer.test.ts src/features/audio/__tests__/AudioUploadPage.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): I8+I9+I10 abortable upload workers, terminal-state reducer guards, explicit part offsets, finalize survives latest-task failure"
```

---

## Task 8: I14 confirm dialogs + I15 hardcoded credentials

**Mapping:** I14 (`SpeakerProfileCard.tsx` 撤销授权/删除档案 fire on a single click; documents/exports already have dialog patterns to mirror), I15 (`LoginPage.tsx:9-10` ships `admin`/`admin123` in the production bundle).

**Files:**
- Create: `src/shared/components/ConfirmDialog.tsx`
- Modify: `src/features/speakers/SpeakerProfileCard.tsx`, `src/features/speakers/queries.ts`
- Create: `src/features/speakers/__tests__/SpeakerProfileCard.confirm.test.tsx`
- Modify: `src/features/auth/LoginPage.tsx`, `src/features/auth/__tests__/LoginPage.test.tsx`

- [ ] **Step 8.1: Shared dialog (markup mirrors ExportsPage's revoke modal classes)**

`src/shared/components/ConfirmDialog.tsx`:

```tsx
interface ConfirmDialogProps {
  title: string;
  description: string;
  confirmLabel: string;
  pending?: boolean;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  title, description, confirmLabel, pending = false, danger = false, onConfirm, onCancel,
}: ConfirmDialogProps) {
  return (
    <div className="modal-backdrop" role="presentation">
      <section
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
      >
        <div className="modal-header">
          <div>
            <h2 id="confirm-dialog-title" className="card-title">{title}</h2>
            <p id="confirm-dialog-description" className="muted">{description}</p>
          </div>
        </div>
        <div className="modal-actions" aria-live="polite">
          <button className="button" type="button" onClick={onCancel} disabled={pending}>取消</button>
          <button
            className={`button ${danger ? "button--danger" : "button--primary"}`}
            type="button"
            onClick={onConfirm}
            disabled={pending}
          >
            {pending ? "处理中…" : confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
```

- [ ] **Step 8.2: Gate the destructive actions in `SpeakerProfileCard.tsx`**

```tsx
import { ConfirmDialog } from "@shared/components/ConfirmDialog";
// state: const [confirmAction, setConfirmAction] = useState<"revoke" | "delete" | null>(null);
// buttons now: onClick={() => setConfirmAction("revoke")} / setConfirmAction("delete")
// at the end of the card JSX:
      {confirmAction ? (
        <ConfirmDialog
          title={confirmAction === "revoke" ? "撤销声纹授权" : "删除声纹档案"}
          description={
            confirmAction === "revoke"
              ? `撤销后 ${profile.displayName ?? profile.personId} 的声纹将不再参与说话人匹配。`
              : `删除后 ${profile.displayName ?? profile.personId} 的声纹档案与全部参考音频将不可恢复。`
          }
          confirmLabel={confirmAction === "revoke" ? "确认撤销" : "确认删除"}
          danger={confirmAction === "delete"}
          pending={confirmAction === "revoke" ? revoke.isPending : remove.isPending}
          onCancel={() => setConfirmAction(null)}
          onConfirm={() => {
            void (confirmAction === "revoke" ? onRevoke() : onDelete()).finally(() => setConfirmAction(null));
          }}
        />
      ) : null}
```

While here, migrate `useRevokeSpeakerProfile`/`useDeleteSpeakerProfile` in `src/features/speakers/queries.ts` to `useApiMutation` (`actionKey: "revoke-speaker-profile"` / `"delete-speaker-profile"`, passing the key through to the Task-3 client signatures) so the dialog's confirm-retry also reuses keys.

- [ ] **Step 8.3: I15 — empty credentials**

`src/features/auth/LoginPage.tsx:9-10`:

```tsx
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
```

Add `placeholder="账号"` / `placeholder="密码"` if desired; do **not** gate prefill on `import.meta.env.DEV` (keep prod and test behavior identical). Update `__tests__/LoginPage.test.tsx` to type credentials before submitting (it currently relies on the prefill):

```tsx
fireEvent.change(screen.getByLabelText("账号"), { target: { value: "admin" } });
fireEvent.change(screen.getByLabelText("密码"), { target: { value: "admin123" } });
```

- [ ] **Step 8.4: Dialog test**

`src/features/speakers/__tests__/SpeakerProfileCard.confirm.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { createQueryClient } from "@shared/queries/queryClient";
import { SpeakerProfileCard } from "../SpeakerProfileCard";

const profile = {
  speakerProfileId: "spk_alice", personId: "alice", displayName: "Alice 张",
  consentStatus: "ACTIVE",
};

function renderCard() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <SpeakerProfileCard profile={profile} setError={() => undefined} />
    </QueryClientProvider>,
  );
}

it("delete requires confirmation; cancel sends nothing (I14)", async () => {
  let deleted = 0;
  server.use(
    http.delete("/api/speaker-profiles/:profileId", () => {
      deleted += 1;
      return HttpResponse.json({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
    }),
  );
  renderCard();

  fireEvent.click(screen.getByRole("button", { name: "删除档案" }));
  const dialog = await screen.findByRole("dialog");
  expect(deleted).toBe(0); // nothing fired yet

  fireEvent.click(screen.getByRole("button", { name: "取消" }));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(deleted).toBe(0);

  fireEvent.click(screen.getByRole("button", { name: "删除档案" }));
  fireEvent.click(await screen.findByRole("button", { name: "确认删除" }));
  await waitFor(() => expect(deleted).toBe(1));
});
```

- [ ] **Step 8.5: Run, type-check, commit**

```bash
npx vitest run src/features/speakers/__tests__/SpeakerProfileCard.confirm.test.tsx src/features/auth/__tests__/LoginPage.test.tsx
npm test
npx tsc --noEmit
git add -A && git commit -m "fix(web): I14+I15 confirm dialogs for voiceprint revoke/delete; remove hardcoded login prefill"
```

---

## Task 9: D6 / I3 — transcript virtualization with `@tanstack/react-virtual`

`TranscriptPage.tsx:199-283` renders every segment (`sortedSegments.map`), violating the SPEC §8 virtual-scroll mandate. **Contract check result (Verification note 4): `GET /meetings/{meetingId}/transcript` has no cursor/limit params — virtualize only.** Cursor pagination (first page ≤200, ≤500/page per SPEC §8.6) requires a contract + Java change first; recorded under Follow-ups.

**Files:**
- Modify: `package.json` (add `@tanstack/react-virtual`)
- Modify: `src/features/transcript/TranscriptPage.tsx`
- Create: `src/features/transcript/__tests__/TranscriptPage.virtual.test.tsx`
- Modify: `src/features/transcript/__tests__/TranscriptPage.test.tsx` (jsdom measurement shim)

- [ ] **Step 9.1: Add the dependency**

```bash
cd apps/meeting-web
npm install @tanstack/react-virtual@^3
```

(~3 KB gzip — well inside the <200 KB first-screen budget; it lands in the lazy transcript chunk anyway.)

- [ ] **Step 9.2: Virtualize the list**

In `TranscriptPage.tsx` — replace the `segmentRefs` map + plain `.map()` with a virtualizer; deep-link scrolling moves from `scrollIntoView` to `scrollToIndex` (the target node may not be mounted when virtualized):

```tsx
import { useVirtualizer } from "@tanstack/react-virtual";

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const rowVirtualizer = useVirtualizer({
    count: sortedSegments.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 120,
    overscan: 8,
    getItemKey: (index) => sortedSegments[index]!.segmentId,
  });

  // Deep-link effect: replace the segmentRefs/scrollIntoView block with:
  useEffect(() => {
    if (!transcript || (!targetSegmentId && !targetStartMs)) return;
    /* …existing match resolution unchanged… */
    if (!match) { setMissingTarget(true); return; }
    setMissingTarget(false);
    setHighlightedSegmentId(match.segmentId);
    const index = sortedSegments.findIndex((s) => s.segmentId === match!.segmentId);
    if (index >= 0) rowVirtualizer.scrollToIndex(index, { align: "center" });
    const timer = window.setTimeout(() => setHighlightedSegmentId(null), 2500);
    return () => window.clearTimeout(timer);
  }, [transcript, targetSegmentId, targetStartMs, sortedSegments, rowVirtualizer]);
```

```tsx
          <div
            className="transcript-list"
            ref={scrollRef}
            data-testid="transcript-scroll"
            style={{ height: "min(70vh, 640px)", overflowY: "auto" }}
          >
            <div style={{ height: rowVirtualizer.getTotalSize(), position: "relative", width: "100%" }}>
              {rowVirtualizer.getVirtualItems().map((virtualItem) => {
                const segment = sortedSegments[virtualItem.index]!;
                return (
                  <article
                    key={segment.segmentId}
                    data-index={virtualItem.index}
                    ref={rowVirtualizer.measureElement}
                    className={`transcript-row${highlightedSegmentId === segment.segmentId ? " transcript-row-highlighted" : ""}`}
                    aria-label={`segment-${segment.segmentId}`}
                    style={{ position: "absolute", top: 0, left: 0, width: "100%", transform: `translateY(${virtualItem.start}px)` }}
                  >
                    {/* …existing row body + edit UI verbatim (measureElement absorbs the edit-mode height change)… */}
                  </article>
                );
              })}
            </div>
          </div>
```

Delete the now-unused `segmentRefs` ref.

- [ ] **Step 9.3: Tests (jsdom has no layout — shim measurements)**

Create `src/features/transcript/__tests__/TranscriptPage.virtual.test.tsx`:

```tsx
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
// reuse the render helper from TranscriptPage.test.tsx (extract to a shared
// local helper if needed)

beforeEach(() => {
  // jsdom reports 0×0 — give the virtualizer a viewport.
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
    width: 800, height: 600, top: 0, left: 0, bottom: 600, right: 800, x: 0, y: 0,
    toJSON: () => ({}),
  } as DOMRect);
});

it("renders a window, not all 1000 segments (I3)", async () => {
  server.use(
    http.get("/api/meetings/:meetingId/transcript", ({ params }) =>
      HttpResponse.json({
        success: true,
        data: {
          meetingId: String(params.meetingId),
          transcriptVersion: 1,
          staleStatus: "CURRENT",
          segments: Array.from({ length: 1000 }, (_, i) => ({
            segmentId: `seg_${i}`,
            speakerLabel: "SPEAKER_00",
            speakerDisplayName: null,
            startMs: i * 2000,
            endMs: i * 2000 + 1800,
            originalText: `第 ${i} 句`,
            editedText: null,
            currentText: `第 ${i} 句`,
            asrConfidence: 0.9,
            diarizationConfidence: 0.9,
            timestampPrecision: "SEGMENT",
          })),
        },
        error: null, requestId: "r", traceId: "t",
      }),
    ),
  );
  renderTranscriptPage();
  await screen.findByText("1000 条");
  await waitFor(() => {
    const rendered = document.querySelectorAll("article[aria-label^='segment-']").length;
    expect(rendered).toBeGreaterThan(0);
    expect(rendered).toBeLessThan(80); // viewport(600)/estimate(120) + 2×overscan(8) ≈ 21
  });
});
```

Apply the same `getBoundingClientRect` shim to the existing `TranscriptPage.test.tsx` (its single-segment fixtures must still render inside the virtual window; with a 600px viewport one segment always renders). If the existing deep-link test asserted `scrollIntoView`, re-point it at the highlight class on `seg_…` after `scrollToIndex`.

- [ ] **Step 9.4: Run, type-check, commit**

```bash
npx vitest run src/features/transcript/__tests__/TranscriptPage.virtual.test.tsx src/features/transcript/__tests__/TranscriptPage.test.tsx
npm test
npx tsc --noEmit
npm run build   # confirm chunking + budget unaffected
git add -A && git commit -m "fix(web): I3 virtualize transcript list with @tanstack/react-virtual; citation deep-link via scrollToIndex"
```

---

## Task 10: Contract drift + stale SPEC text (carried by this review volume)

- [ ] **Step 10.1: `UpdateTranscriptSegmentRequest` drift (fix the YAML to match implementation)**

Verified: public-api.yaml:2809-2814 requires `currentText`, but Java (`TranscriptController` `UpdateSegmentRequest(editedText, editReason, expectedTranscriptVersion)`) and `client.ts:365` both speak `editedText`/`editReason`. In `packages/meeting-contracts/openapi/public-api.yaml` replace the schema:

```yaml
    UpdateTranscriptSegmentRequest:
      type: object
      required: [editedText, expectedTranscriptVersion]
      properties:
        editedText: {type: string}
        editReason: {type: [string, 'null']}
        expectedTranscriptVersion: {type: integer, minimum: 0}
```

```bash
cd packages/meeting-contracts
npm run check
npm run codegen     # commit regenerated types in all consumers (git diff must be clean in CI)
git add -A && git commit -m "fix(contracts): UpdateTranscriptSegmentRequest matches shipped editedText/editReason shape"
```

- [ ] **Step 10.2: Delete the stale Phase-K acceptance item**

`apps/meeting-web/SPEC.md` §10.2 item 9 (line 306: "`CONFIDENTIAL` / `SECRET` 自动 LLM 相关入口 fail closed…") contradicts §1.4 and the Phase-K removal of security levels. Delete the line and renumber item 10 → 9.

```bash
git add apps/meeting-web/SPEC.md && git commit -m "docs(web): drop stale §10.2 security-level acceptance item (Phase K removed classification)"
```

---

## Task 11: Minor findings — triage table

Apply as a single sweep commit (`fix(web): minor review-P3 triage sweep`) plus the noted exceptions. Items marked **folded** were already absorbed by an earlier task; items marked **deferred** get a `// TODO(review-P3)` comment + Follow-ups entry instead of code now.

| Location | Problem | One-line fix |
|---|---|---|
| `client.ts:82-88` | every 404 → `TASK_NOT_FOUND` | parse the 404 body's envelope and rethrow its real `error.code`/`message`; fall back to `TASK_NOT_FOUND` only for non-JSON bodies |
| `error-mapper.ts` | `RAG_RATE_LIMITED` (error-codes.yaml:307) unmapped; unknown codes drop server message; new file-upload codes unmapped | add `RAG_RATE_LIMITED: "请求过于频繁，请稍后再试"`, `FILE_MIME_NOT_ALLOWED: "文件类型不支持"`, `FILE_UPLOAD_NOT_FOUND: "上传会话不存在"`; change to `getUserMessage(code: string, fallback?: string)` returning `ERROR_MESSAGES[code] ?? fallback ?? code` (call sites pass `error.message`) |
| `DocumentsPage.tsx:105-112` | `handleReindex` try/finally without catch → unhandled rejection | add `catch { /* surfaced via reindex.errorMessage */ }` and render the wrapper's `errorMessage` |
| `MinutesPage.tsx:137` | citation links to `/transcript` without target | `to={`/meetings/${meetingId}/transcript?segmentId=${ev.segmentId ?? ""}${typeof ev.startMs === "number" ? `&startMs=${ev.startMs}` : ""}`}` |
| `ItemsPage.tsx:214` | evidence is a non-clickable `<span className="link">` | replace with the same `<Link to={…?segmentId=&startMs=}>` as MinutesPage |
| `ItemsPage.tsx` (whole) | bypasses TanStack Query (`useState`/`useEffect`, last-resolved-wins race, full reload flash on accept/reject) | **deferred**: migrate to `useQuery(["action-items"|"decisions"|"risks", meetingId])` + `useApiMutation` — keys already registered in `invalidateAfter` (Task 4); tracked in Follow-ups |
| `ExportsPage.tsx:173-181` | `handleCancel` has no per-row pending → double-click duplicate cancels | add `const [cancellingId, setCancellingId] = useState<string|null>(null)`; guard + `disabled={cancellingId === job.exportId}` on the row button |
| `queryClient.ts:8` | `retry: 2` retries 4xx too (SPEC §5.2: network/429/503/retryable only) | `retry: (count, error) => { const e = error as ApiClientError; return count < 2 && (e?.retryable === true \|\| e?.status === 429 \|\| e?.status === 503 \|\| e?.code === "DEPENDENCY_UNAVAILABLE"); }` |
| `SpeakerEnrollPanel.tsx:302` objectURL leak; `:188` ETag preference; `:97` unbounded polling | — | **folded** into Task 5 (Step 5.5: `previewUrl` memo + revoke; PUT ETag first; 120s poll cap) |
| forms (repo-wide) | no `react-hook-form` + `zod` despite stack mandate; no dirty-state guard on transcript editor | **deferred**: introducing the form stack is a feature, not a review fix; tracked in Follow-ups |
| `TaskProgressPage.tsx` | omits per-step `errorCode`/`retryable`/`maxAttempts`/timestamps; cancel enabled during `CANCEL_PENDING` | add 「错误」 column rendering `step.errorCode` + retryable pill where present; `disabled={!!isTerminal \|\| cancel.isPending \|\| snapshot.status === "CANCEL_PENDING"}` |
| `MinutesPage.tsx:70-75` | one banner for `STALE`/`REBUILD_QUEUED`/`REBUILDING`/`FAILED` (SPEC §4.2 wants distinct presentations) | branch on `minutes.staleStatus`: `STALE` → warn + regenerate CTA; `REBUILD_QUEUED`/`REBUILDING`/`VALIDATING` → info "重建中，内容可能不是最新"; `FAILED` → danger + `getUserMessage(errorCode)` + retry CTA |
| `MeetingListPage` | no status filter; client-side search only | **deferred**: needs list-endpoint query params from the contract; tracked in Follow-ups |
| `StartTaskPanel.tsx:9` | dev fixture `audio_fixture_01` default ships in prod UI | `useState("")` + `placeholder={import.meta.env.DEV ? "如 audio_fixture_01" : "音频文件 ID"}`; remove the `\|\| "audio_fixture_01"` fallback in `handleStart` and disable the button when empty |
| contract drift `UpdateTranscriptSegmentRequest` | — | **folded** into Task 10.1 (fix(contracts) commit) |
| `SPEC.md` §10.2 item 9 | — | **folded** into Task 10.2 (docs commit) |

Run after the sweep:

```bash
npm test && npx tsc --noEmit && npm run lint
git add -A && git commit -m "fix(web): minor review-P3 triage sweep (404 code passthrough, error-mapper additions, citation deep-links, retry predicate, distinct STALE banners, per-row cancel pending, fixture default)"
```

---

## Task 12: Final verification

- [ ] `cd apps/meeting-web && npm test` — full suite green
- [ ] `npx tsc --noEmit` — clean
- [ ] `npm run lint` — clean
- [ ] `npm run build` — succeeds; first-screen budget unaffected (react-virtual lives in the lazy transcript chunk)
- [ ] `cd packages/meeting-contracts && npm run check && npm run codegen && git diff --exit-code` — no codegen drift
- [ ] Manual smoke (optional, against local stack): login → logout button works; transcript page virtual-scrolls; enrollment from 声纹档案 page never creates/touches a meeting (watch the network tab for `/api/files`); upload cancel stops network traffic immediately.

---

## Follow-ups (explicitly out of P3 scope — do not silently expand)

1. **Backend `/auth/refresh`** (meeting-api + public-api.yaml): the client-side single-flight architecture from Task 2 is wired and constant-isolated; backend must add the endpoint, the HttpOnly refresh cookie, and the non-HttpOnly `XSRF-TOKEN` CSRF cookie per SPEC §5.2.
2. **Transcript cursor pagination** (contracts + Java + web): `GET /meetings/{meetingId}/transcript` needs `cursor`/`limit` params before the SPEC §8.6 "first page ≤200, ≤500/page" rule can be honored; Task 9 delivers the rendering half (virtualization) only.
3. **Honest `audio/webm`/`audio/mp4` support on `/files`** (contracts enum + Java `MIME_WHITELIST` + 3-language codegen): removes the BFF-parity `audio/wav` labeling wart from Task 5.
4. **ItemsPage TanStack Query migration** (keys pre-registered in `invalidateAfter`).
5. **react-hook-form + zod** adoption for Login/MeetingCreate/Export forms + transcript-editor dirty guard.
6. **MeetingListPage server-side status filter** once the list endpoint grows filter params.
