# AI Worker Web Remediation Plan (Review P4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 2 Critical and 10 Important findings from the 2026-06-12 review of the operator workstation SPA: wrong-person voiceprint commits, dead-end enrollment sessions, broken idempotency, duplicate meetings on retry, runaway SSE reconnects, missing query invalidation, silent export timeouts, racing speaker decisions, whole-file hashing, and shipped default credentials — plus a triage table for 12 Minor items.

**Architecture:** Targeted fixes inside `apps/ai-worker-web/` (React 18.3, Vite 5, TS strict, TanStack Query, react-router-dom, hand-rolled pub/sub auth store, base `/workstation/`) plus one defense-in-depth guard in the ai-worker admin BFF (`apps/ai-worker/ai_worker/admin/enrollment.py`). Java stays the sole DB writer and permission authority; the BFF stays a thin pass-through. Per the review's root-cause note, fixes touching the imperative useState+async flows move only the affected pieces toward TanStack Query invalidation — **no wholesale refactor**.

**Tech Stack:** TypeScript 5 / React 18.3 / Vitest 2 + React Testing Library (run via `node scripts/run-vitest.mjs`), Python 3.11 / FastAPI / pytest-asyncio + httpx ASGITransport for the BFF sub-task.

**Branch:** `fix/review-remediation-p4-worker-web`
**Source review:** 2026-06-12 four-workspace code review — this volume fixes ai-worker-web Critical #1–#2 and Important #1–#10.

---

## Verification notes — review claims checked against source (2026-06-12)

Every Critical/Important file was read before writing this plan. Results:

1. **Path corrections.** The review cites `features/…` paths for pages; pages actually live in `src/pages/` (`EnrollmentPage.tsx`, `NewMeetingPage.tsx`, `MeetingDetailPage.tsx`, `LoginPage.tsx`, `PeoplePage.tsx`, `SpeakerProfilesPage.tsx`). Query hooks live in `src/features/meetings/queries.ts` and `src/features/speaker-profiles/queries.ts`; the query client at `src/shared/queries/queryClient.ts`. Line numbers in the review map accurately onto the real files. All task file references below use the **real** paths.
2. **C1, C2, I1, I2, I4, I5, I6, I7, I8, I9, I10 — confirmed exactly as reviewed.** Notably: `client.ts:78` always generates a fresh UUID `Idempotency-Key` (the `ApiCallOptions.idempotencyKey` plumbing exists at `client.ts:50` and is honored — D3's assumption verified); the BFF derives all five Java sub-call keys from `idempotency_key or session_id` (`enrollment.py:194, 223, 248, 289, 312`), so the `session_id` fallback can never engage today.
3. **I3 — confirmed in substance, one nuance.** `handlers.lastEventId` is technically re-read from the handlers object on every reconnect iteration (`client.ts:174` is inside the `while`), not "read once" — but no caller ever updates it and the client never parses `id:` lines from the stream, so the resume value is permanently stale. The fix (Task 6) makes the client itself track the last seen event id.
4. **M5 — valid but benign today**: the read and `subscribe` happen in the same synchronous effect body, so no update can interleave in single-threaded JS. Kept in the triage table as a hygiene reorder.
5. **e2e impact of I10/D12**: both Playwright specs (`e2e/enrollment-new-person.spec.ts:30`, `e2e/new-meeting-end-to-end.spec.ts:73`) bypass the login form entirely via `?playwright-skip-auth=1` (that backdoor is M4, triage-only). Removing the prefilled credentials breaks **no e2e**, but `LoginPage.test.tsx:49-52` asserts the prefilled body and must be updated (Task 11).
6. **BFF error codes are not contract-governed.** `ENROLLMENT_SESSION_NOT_FOUND` / `ENROLLMENT_NOT_PREVIEWED` / `ENROLLMENT_PERSON_REQUIRED` exist only in `enrollment.py`; `grep ENROLLMENT_ packages/meeting-contracts/` is empty. Adding `ENROLLMENT_PERSON_MISMATCH` (Task 2) therefore requires **no** contracts change / codegen run.
7. **M1 confirmed**: contract `ProcessingTaskStep.progress` is `integer 0–100` (`public-api.yaml`, `ProcessingTaskStep` schema), so `normalizeProgress`'s `<= 1 ? *100` heuristic maps a real 1% to 100%, and it is applied twice (seed via `normalizeStep` + render at line 305).

---

## File Structure

```
apps/ai-worker-web/
├── src/
│   ├── pages/
│   │   ├── EnrollmentPage.tsx            # MODIFY — Tasks 1, 3, 5 (C1 lock, C2 reset, I5 invalidate)
│   │   ├── EnrollmentPage.test.tsx       # MODIFY — Tasks 1, 3, 5
│   │   ├── NewMeetingPage.tsx            # MODIFY — Task 8 (I2 resume, I5 invalidate, M2 abort filter)
│   │   ├── NewMeetingPage.test.tsx       # MODIFY — Task 8
│   │   ├── MeetingDetailPage.tsx         # MODIFY — Tasks 7, 9 (I3/I4 lifecycle; I6/I7/I8 interactions)
│   │   ├── MeetingDetailPage.test.tsx    # MODIFY — Tasks 7, 9
│   │   ├── LoginPage.tsx                 # MODIFY — Task 11 (I10)
│   │   └── LoginPage.test.tsx            # MODIFY — Task 11
│   ├── features/meetings/queries.ts      # MODIFY — Task 5 (M8: delete dead enrollment hooks)
│   └── shared/
│       ├── api/
│       │   ├── client.ts                 # MODIFY — Task 6 (I3 SSE lifecycle)
│       │   ├── client.test.ts            # MODIFY — Task 6
│       │   ├── endpoints.ts              # MODIFY — Tasks 1, 4, 5, 8 (commit body+key, step keys, M8 removal)
│       │   └── endpoints.test.ts         # MODIFY — Tasks 4, 5, 8
│       ├── upload/
│       │   ├── MultipartUploader.ts      # MODIFY — Task 10 (I9)
│       │   └── MultipartUploader.test.ts # MODIFY — Task 10
│       └── utils/
│           ├── sha256-stream.ts          # CREATE — Task 10 (ported from meeting-web)
│           └── sha256-stream.test.ts     # CREATE — Task 10 (ported from meeting-web)
apps/ai-worker/
├── ai_worker/admin/enrollment.py         # MODIFY — Task 2 (C1 BFF guard)
└── tests/admin/test_enrollment_session.py # MODIFY — Task 2
```

Triage-only files (Task 12, no plan-driven edits unless picked up later): `src/shared/auth/useAuth.ts` (M4, M5), `src/App.tsx` (M6), `src/pages/PeoplePage.tsx` (M7), `src/shared/api/client.ts:77` (M9), `src/shared/components/PersonCreateModal.tsx` + modal blocks in `MeetingDetailPage.tsx` / `SpeakerProfilesPage.tsx` (M11), `src/shared/auth/store.ts` (M12).

## Conventions

- SPA commands run from `apps/ai-worker-web/`: `npm test` (= `node scripts/run-vitest.mjs run`), single file `node scripts/run-vitest.mjs run src/path/to/test.tsx`, `npm run type-check`, `npm run lint`.
- BFF commands run from `apps/ai-worker/`: `uv run pytest tests/admin/test_enrollment_session.py -v`, `uv run pyright ai_worker/`.
- Commit prefixes: `fix(workstation): …` for SPA changes, `fix(worker): …` for BFF changes.
- Note: `tsconfig.json` excludes `src/**/*.test.ts(x)` from `tsc`, so `npm run type-check` won't catch test-file type errors — vitest's esbuild will. Run `npm test` after every task.

**Setup step (once):**

- [ ] `git checkout master && git pull && git checkout -b fix/review-remediation-p4-worker-web`

---

## Task 1: [C1] Lock person selection to the enrollment session (SPA)

The BFF binds `person_id` at session creation (`enrollment.py:71-74`) and commit enrolls `session.person_id` (`enrollment.py:146, 172-179`); the UI's later selection is never sent. Today (`src/pages/EnrollmentPage.tsx`): the radio list (lines 111-131), the search input (line 101), and the `PersonCreateModal` trigger (line 134) all stay live after 创建录入会话, while the create button is `disabled={!personId || busy || !!session}` (line 159). Selecting 李四 after binding 张三 silently enrolls 李四's audio under 张三. Per **D1**: once a session exists, lock all person controls, show the bound person, and send the SPA's currently selected `personId` with commit so the BFF (Task 2) can 409 on mismatch.

**Files:**
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts` (commitEnrollment gains `personId`)

- [ ] **Step 1.1 — Write failing tests**

In `src/pages/EnrollmentPage.test.tsx`, add two tests inside the existing `describe`, and update the existing commit assertion:

```tsx
  it("locks person selection once a session exists (C1)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    // mockResolvedValueOnce (not mockResolvedValue): the suite's beforeEach uses
    // vi.clearAllMocks(), which does NOT reset persistent implementations — a
    // persistent override here would leak into later tests.
    vi.mocked(endpoints.searchPersons).mockResolvedValueOnce([
      { personId: "p-zhang", displayName: "张三", email: null, externalId: null, createdAt: "" },
      { personId: "p-li", displayName: "李四", email: null, externalId: null, createdAt: "" },
    ]);

    renderEnrollmentPage();
    fireEvent.change(screen.getByLabelText("搜索人员"), { target: { value: "张" } });
    fireEvent.click(await screen.findByRole("radio", { name: /张三/ }));
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));
    await screen.findByTestId("session-id");

    expect(screen.getByRole("radio", { name: /张三/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /李四/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /新建人员/ })).toBeDisabled();
    expect(screen.getByLabelText("搜索人员")).toBeDisabled();
    expect(screen.getByTestId("session-person-lock")).toHaveTextContent("张三");
  });

  it("sends the selected personId with commit for BFF mismatch detection (C1)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.previewEnrollment).mockResolvedValueOnce({
      sessionId: "s1", state: "PREVIEWED", personId: "p-new", qualityScore: 0.72,
    });
    vi.mocked(endpoints.commitEnrollment).mockResolvedValueOnce({
      sessionId: "s1", state: "COMMITTED", personId: "p-new", qualityScore: 0.72,
      profileId: "sp_01", fileId: "file_01",
    });

    renderEnrollmentPage();
    await createSelectedSession();
    chooseEnrollmentAudio();
    fireEvent.click(screen.getByRole("button", { name: /上传并预览/ }));
    expect(await screen.findByText("质量分 0.72")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /确认录入/ }));

    await waitFor(() => expect(endpoints.commitEnrollment).toHaveBeenCalledWith("s1", "p-new"));
  });
```

In the existing test `"commits enrollment after a passing quality preview"`, change line 100:

```tsx
    await waitFor(() => expect(endpoints.commitEnrollment).toHaveBeenCalledWith("s1", "p-new"));
```

- [ ] **Step 1.2 — Run, expect RED**

```bash
node scripts/run-vitest.mjs run src/pages/EnrollmentPage.test.tsx
```
Expected failures: `locks person selection…` fails on `expect(…radio 张三).toBeDisabled()` (radio has no `disabled` attr) and missing `session-person-lock` testid; both commit-arg assertions fail with `commitEnrollment` called with `["s1"]` instead of `["s1", "p-new"]`.

- [ ] **Step 1.3 — Implement**

`src/shared/api/endpoints.ts` — replace the `commitEnrollment` export (lines 49-53):

```ts
export const commitEnrollment = (sessionId: string, personId?: string | null) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/commit`,
    { method: "POST", body: { personId: personId ?? null } },
  );
```

`src/pages/EnrollmentPage.tsx`:

1. Add a derived flag next to the other derived consts (after line 85 `const committed = …`):

```tsx
  const sessionLocked = !!session;
```

2. Disable the search input (line 101-108): add `disabled={sessionLocked}` to the `<input id="enroll-person-search" …>`.

3. Disable each radio (line 116-125): add `disabled={sessionLocked}` to the `<input type="radio" …>`.

4. Replace the step-1 toolbar block (lines 133-142) so the modal trigger is disabled and the bound person is shown:

```tsx
        <div className="toolbar">
          <button
            className="button button--secondary"
            type="button"
            onClick={() => setPersonModalOpen(true)}
            disabled={sessionLocked}
          >
            + 新建人员
          </button>
          {sessionLocked ? (
            <span className="page-subtitle" data-testid="session-person-lock">
              已绑定人员：<span translate={selectedPerson ? undefined : "no"}>{selectedPersonLabel}</span>
              （更换人员请先「重新开始」）
            </span>
          ) : selectedPersonLabel ? (
            <span className="page-subtitle">
              已选择：<span translate={selectedPerson ? undefined : "no"}>{selectedPersonLabel}</span>
            </span>
          ) : null}
        </div>
```

5. In `handleCommit` (line 73), pass the current selection:

```tsx
      const committed = await commitEnrollment(session.sessionId, personId);
```

- [ ] **Step 1.4 — Run, expect GREEN**

```bash
node scripts/run-vitest.mjs run src/pages/EnrollmentPage.test.tsx
node scripts/run-vitest.mjs run src/shared/api/endpoints.test.ts
npm run type-check && npm run lint
```
All EnrollmentPage tests pass (the existing `已选择：p-link` assertion still passes because the lock text only renders once a session exists).

- [ ] **Step 1.5 — Commit**

```bash
git add apps/ai-worker-web/src/pages/EnrollmentPage.tsx apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx apps/ai-worker-web/src/shared/api/endpoints.ts
git commit -m "fix(workstation): lock person selection to the bound enrollment session (C1)"
```

---

## Task 2: [C1] BFF defense-in-depth — `ENROLLMENT_PERSON_MISMATCH` guard

Even with the UI locked, a stale tab / scripted client can still commit a session while believing a different person is selected. Per **D1**, `POST /admin/enrollment/sessions/{id}/commit` accepts an optional `personId` in the body and returns **409 `ENROLLMENT_PERSON_MISMATCH`** when it differs from `session.person_id`. This is a BFF-local error code (same non-contract family as `ENROLLMENT_SESSION_NOT_FOUND`) — **no contracts/codegen change**.

**Files:**
- Modify: `apps/ai-worker/ai_worker/admin/enrollment.py` (commit handler, lines 125-145 region)
- Modify: `apps/ai-worker/tests/admin/test_enrollment_session.py`

- [ ] **Step 2.1 — Write failing test**

Append to `apps/ai-worker/tests/admin/test_enrollment_session.py` (reuses the module's `_StubJavaClient`, `_FakeUploadAsyncClient`, `make_admin_token` helpers):

```python
@pytest.mark.asyncio
async def test_commit_rejects_person_mismatch_with_409(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    import ai_worker.admin.enrollment as enrollment_module

    monkeypatch.setattr(enrollment_module.httpx, "AsyncClient", _FakeUploadAsyncClient)
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"audio-bytes")
    session.touch_audio(audio)
    session.touch_preview(0.9, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(
            f"/admin/enrollment/sessions/{session.session_id}/commit",
            headers=headers,
            json={"personId": "person_02"},
        )

    assert response.status_code == 409
    body = response.json()
    assert body["error"]["code"] == "ENROLLMENT_PERSON_MISMATCH"
    assert body["error"]["details"] == {"sessionPersonId": "person_01", "requestedPersonId": "person_02"}
    assert java.received == []                      # no Java write happened
    assert await store.get(session.session_id) is not None   # session intact for reset/retry


@pytest.mark.asyncio
async def test_commit_accepts_matching_person_id_in_body(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    import ai_worker.admin.enrollment as enrollment_module

    monkeypatch.setattr(enrollment_module.httpx, "AsyncClient", _FakeUploadAsyncClient)
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"audio-bytes")
    session.touch_audio(audio)
    session.touch_preview(0.9, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(
            f"/admin/enrollment/sessions/{session.session_id}/commit",
            headers=headers,
            json={"personId": "person_01"},
        )

    assert response.status_code == 200
    assert response.json()["data"]["state"] == "COMMITTED"
```

- [ ] **Step 2.2 — Run, expect RED**

```bash
cd apps/ai-worker && uv run pytest tests/admin/test_enrollment_session.py -v
```
Expected: `test_commit_rejects_person_mismatch_with_409` fails — commit ignores the body today and proceeds to a 200 COMMITTED with `person_01`. The matching-person test passes already (body ignored) — it pins the non-breaking path.

- [ ] **Step 2.3 — Implement**

In `apps/ai-worker/ai_worker/admin/enrollment.py`, change the commit handler signature to take the request, and insert the guard right after the session-not-found check (`Request` is already imported at line 21):

```python
    @router.post("/sessions/{session_id}/commit", status_code=200)
    async def commit(
        session_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        session = await session_store.get(session_id)
        if session is None or session.tenant_id != claims.tenant_id:
            return error(status_code=404, code="ENROLLMENT_SESSION_NOT_FOUND",
                         message="session not found or expired", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)
        body = await request.json() if (await request.body()) else {}
        requested_person_id = body.get("personId") if isinstance(body, dict) else None
        if requested_person_id and requested_person_id != session.person_id:
            return error(
                status_code=409,
                code="ENROLLMENT_PERSON_MISMATCH",
                message="commit personId does not match the person bound at session creation",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
                details={"sessionPersonId": session.person_id, "requestedPersonId": requested_person_id},
            )
        if session.state != "PREVIEWED":
            ...  # rest of the handler unchanged
```

(Only the signature line `request: Request,` and the `body…mismatch` block are new; everything from `if session.state != "PREVIEWED":` down is untouched. A `null`/absent `personId` keeps the old behavior — SPA versions that don't send it, and the existing `ENROLLMENT_PERSON_REQUIRED` path for unbound sessions, are unaffected.)

- [ ] **Step 2.4 — Run, expect GREEN**

```bash
cd apps/ai-worker && uv run pytest tests/admin/test_enrollment_session.py -v && uv run pyright ai_worker/
```
All tests pass, including the pre-existing commit tests (they POST without a body → `requested_person_id` is `None` → guard skipped).

- [ ] **Step 2.5 — Commit**

```bash
git add apps/ai-worker/ai_worker/admin/enrollment.py apps/ai-worker/tests/admin/test_enrollment_session.py
git commit -m "fix(worker): 409 ENROLLMENT_PERSON_MISMATCH when commit personId differs from session binding (C1)"
```

---

## Task 3: [C2] Session reset path + `ENROLLMENT_SESSION_NOT_FOUND` recovery

`EnrollmentPage.tsx:159` disables 创建录入会话 with `!!session` and nothing ever sets `session` back to `null`. BFF sessions are in-process (`session_store.py:63`) and TTL-expire (`session_store.py:102-106`); after a worker restart or TTL, preview/commit return 404 `ENROLLMENT_SESSION_NOT_FOUND` (`enrollment.py:105-108, 134-137`) forever; only recovery is a full page reload. Per **D2**: add a 重新开始 action clearing session/file/error state; any `ENROLLMENT_SESSION_NOT_FOUND` response surfaces a dedicated banner that auto-offers reset; resetting re-enables the create button. Person selection survives reset (it is the one thing worth keeping).

**Files:**
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx`

- [ ] **Step 3.1 — Write failing tests**

Add `within` to the RTL import at the top of `EnrollmentPage.test.tsx`:

```tsx
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
```

Add two tests:

```tsx
  it("offers 重新开始 which clears the session and re-enables creation (C2)", async () => {
    renderEnrollmentPage("/enrollment?personId=p-link");
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));
    await screen.findByTestId("session-id");
    expect(screen.getByRole("button", { name: /创建录入会话/ })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: /重新开始/ }));

    expect(screen.queryByTestId("session-id")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /创建录入会话/ })).toBeEnabled();
    // person selection survives the reset
    expect(screen.getByText("p-link").closest(".page-subtitle")).toHaveTextContent("已选择：p-link");
  });

  it("surfaces a dedicated session-lost banner with reset on ENROLLMENT_SESSION_NOT_FOUND (C2)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.uploadEnrollmentAudio).mockRejectedValueOnce(new ApiError(
      404,
      { code: "ENROLLMENT_SESSION_NOT_FOUND", message: "session not found or expired", retryable: false },
      "r",
      "t",
    ));

    renderEnrollmentPage("/enrollment?personId=p-link");
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));
    await screen.findByTestId("session-id");
    chooseEnrollmentAudio();
    fireEvent.click(screen.getByRole("button", { name: /上传并预览/ }));

    const banner = await screen.findByTestId("session-lost");
    expect(banner).toHaveTextContent(/录入会话已失效/);

    fireEvent.click(within(banner).getByRole("button", { name: /重新开始/ }));

    expect(screen.queryByTestId("session-lost")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /创建录入会话/ })).toBeEnabled();
  });
```

- [ ] **Step 3.2 — Run, expect RED**

```bash
node scripts/run-vitest.mjs run src/pages/EnrollmentPage.test.tsx
```
Expected: both fail — no `重新开始` button exists, no `session-lost` testid; the second test currently renders the generic error block instead.

- [ ] **Step 3.3 — Implement**

`src/pages/EnrollmentPage.tsx`:

1. New state + helpers (after the `busy` state, line 30):

```tsx
  const [sessionLost, setSessionLost] = useState(false);

  const handleReset = () => {
    setSession(null);
    setFile(null);
    setError(null);
    setSessionLost(false);
  };
```

2. `handleStart` also clears the lost flag on a fresh session (inside the `try`, after `setSession(s)`): add `setSessionLost(false);`.

3. Route session-loss errors in `handleUploadAndPreview` and `handleCommit` — replace each `catch (e) { setError(formatError(e)); }` with:

```tsx
    } catch (e) {
      if (isSessionLost(e)) {
        setSessionLost(true);
      } else {
        setError(formatError(e));
      }
    } finally {
```

and add next to `formatError` at the bottom of the file:

```tsx
function isSessionLost(e: unknown): boolean {
  return e instanceof ApiError && e.error.code === "ENROLLMENT_SESSION_NOT_FOUND";
}
```

4. Render the reset button in step 2, next to the create button (after the `data-testid="session-id"` paragraph):

```tsx
        {session ? (
          <button className="button button--ghost" type="button" onClick={handleReset} disabled={busy}>
            重新开始
          </button>
        ) : null}
```

5. Render the dedicated banner above the generic error block (before the `{error || personSearch.error ? …}` block at the end):

```tsx
      {sessionLost ? (
        <div className="banner banner--danger" role="alert" data-testid="session-lost">
          <strong className="banner__title">录入会话已失效</strong>
          <span className="banner__body">
            会话可能已超时，或 worker 服务已重启（录入会话不持久化）。请重新开始本次录入；已选择的人员会保留。
          </span>
          <button className="button button--secondary" type="button" onClick={handleReset}>
            重新开始
          </button>
        </div>
      ) : null}
```

- [ ] **Step 3.4 — Run, expect GREEN**

```bash
node scripts/run-vitest.mjs run src/pages/EnrollmentPage.test.tsx
npm run type-check && npm run lint
```
Note: the first C2 test's `getByRole("button", { name: /重新开始/ })` is unambiguous because the banner button only renders when `sessionLost` is true; the banner test disambiguates via `within(banner)`.

- [ ] **Step 3.5 — Commit**

```bash
git add apps/ai-worker-web/src/pages/EnrollmentPage.tsx apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx
git commit -m "fix(workstation): enrollment session reset path + session-lost recovery banner (C2)"
```

---

## Task 4: [I1] `Idempotency-Key = sessionId` for enrollment commit

`client.ts:78` falls back to a fresh UUID per write; `commitEnrollment` passes no key, so the BFF's `idempotency_key or session_id` derivation (`enrollment.py:194, 223, 248, 289, 312`) never reaches the stable fallback — a retried commit after a mid-orchestration failure creates a second speaker profile + file + enrollment in Java. Per **D3**: pass the natural business key. The `ApiCallOptions.idempotencyKey` plumbing is verified working (`client.ts:50, 78`; covered by the existing `"adds Idempotency-Key on writes"` test).

**Files:**
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.test.ts`

**Core change** — `commitEnrollment` (already two-arg after Task 1):

```ts
export const commitEnrollment = (sessionId: string, personId?: string | null) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/commit`,
    { method: "POST", body: { personId: personId ?? null }, idempotencyKey: sessionId },
  );
```

**Test** — add to `endpoints.test.ts` (add `commitEnrollment` to the import list):

```ts
  it("commits enrollment with the session id as Idempotency-Key (I1)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ sessionId: "s1", state: "COMMITTED", personId: "p1" }));

    await commitEnrollment("s1", "p1");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/admin/enrollment/sessions/s1/commit");
    const [, init] = fetchMock.mock.calls[0]!;
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("s1");
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ personId: "p1" });
  });
```

- [ ] Add the test, run `node scripts/run-vitest.mjs run src/shared/api/endpoints.test.ts` → fails with a UUID-shaped key
- [ ] Apply the change, re-run → passes; run `npm test` (whole suite — EnrollmentPage mocks `commitEnrollment`, so its tests are unaffected)
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/shared/api/endpoints.ts apps/ai-worker-web/src/shared/api/endpoints.test.ts
git commit -m "fix(workstation): commitEnrollment sends sessionId as Idempotency-Key so BFF sub-call keys are stable (I1)"
```

> General rule going forward (D3): mutations with a natural business key pass it via `idempotencyKey`; the auto-UUID fallback remains only for genuinely fire-once writes. Task 8 applies this to the meeting-creation steps.

---

## Task 5: [I5 + M8] Invalidate speaker-profiles after commit; delete dead enrollment hooks

The only `invalidateQueries` in the app is voiceprint revoke (`src/features/speaker-profiles/queries.ts:17`). With `staleTime: 30_000` + `refetchOnWindowFocus: false` (`src/shared/queries/queryClient.ts:7-10`), a freshly committed voiceprint is invisible in 声纹档案 for 30s, prompting operators to re-do operations (feeding C1/I1). Per **D7**, invalidate `["admin", "speaker-profiles"]` after commit (exact prefix of the key `["admin", "speaker-profiles", personId ?? null]` used by `useSpeakerProfilesQuery`). The `["admin", "meetings"]` invalidation lands with the `startProcessing` rewrite in Task 8 (cleaner diff). M8's dead code — the never-imported enrollment mutation hooks in `src/features/meetings/queries.ts:27-44` (and the equally dead `useSearchPersonsQuery`) plus `endpoints.getProcessingTask` — is removed here rather than adopted: EnrollmentPage's multi-step session ritual stays imperative by design (the architectural note forbids a wholesale refactor), and Task 7's polling targets the aggregate, not the task endpoint.

**Files:**
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx`
- Modify: `apps/ai-worker-web/src/features/meetings/queries.ts`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts` + `endpoints.test.ts` (drop `getProcessingTask`)

**Core change 1** — `EnrollmentPage.tsx`:

```tsx
import { useQueryClient } from "@tanstack/react-query";
// inside the component:
  const queryClient = useQueryClient();
// in handleCommit, after `setSession(committed);`:
      void queryClient.invalidateQueries({ queryKey: ["admin", "speaker-profiles"] });
```

**Core change 2** — `src/features/meetings/queries.ts` shrinks to:

```ts
import { useQuery } from "@tanstack/react-query";
import { listAdminMeetings } from "@/shared/api/endpoints";
import type { MeetingSummaryDTO } from "@/shared/api/types";

export function useAdminMeetingsQuery() {
  return useQuery<MeetingSummaryDTO[]>({
    queryKey: ["admin", "meetings"],
    queryFn: () => listAdminMeetings(),
  });
}
```

**Core change 3** — `endpoints.ts`: delete `getProcessingTask` (lines 140-141) and the now-unused `ProcessingTaskDTO` import; keep `processingTaskEventsUrl`. In `endpoints.test.ts`, drop `getProcessingTask` from the import and replace the `"exposes task detail and SSE helpers"` test with:

```ts
  it("exposes the processing-task SSE events URL helper", () => {
    expect(processingTaskEventsUrl("task 1")).toBe("/api/processing-tasks/task%201/events");
  });
```

**Test wiring** — `useQueryClient` requires a provider; update the render helper in `EnrollmentPage.test.tsx` (all existing tests keep working via the default argument):

```tsx
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

function renderEnrollmentPage(path = "/enrollment", queryClient = new QueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/enrollment" element={<EnrollmentPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
```

**Test** — add to `EnrollmentPage.test.tsx`:

```tsx
  it("invalidates the speaker-profiles list after a successful commit (I5)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.previewEnrollment).mockResolvedValueOnce({
      sessionId: "s1", state: "PREVIEWED", personId: "p-new", qualityScore: 0.72,
    });
    vi.mocked(endpoints.commitEnrollment).mockResolvedValueOnce({
      sessionId: "s1", state: "COMMITTED", personId: "p-new", qualityScore: 0.72,
      profileId: "sp_01", fileId: "file_01",
    });
    const queryClient = new QueryClient();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderEnrollmentPage("/enrollment", queryClient);
    await createSelectedSession();
    chooseEnrollmentAudio();
    fireEvent.click(screen.getByRole("button", { name: /上传并预览/ }));
    expect(await screen.findByText("质量分 0.72")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /确认录入/ }));

    await screen.findByText(/状态: COMMITTED/);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["admin", "speaker-profiles"] });
  });
```

- [ ] Update the render helper, add the test, run `node scripts/run-vitest.mjs run src/pages/EnrollmentPage.test.tsx` → new test RED (no invalidation call)
- [ ] Apply changes 1–3, run `npm test` and `npm run type-check` (catches any leftover import of the deleted hooks) → GREEN
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/pages/EnrollmentPage.tsx apps/ai-worker-web/src/pages/EnrollmentPage.test.tsx apps/ai-worker-web/src/features/meetings/queries.ts apps/ai-worker-web/src/shared/api/endpoints.ts apps/ai-worker-web/src/shared/api/endpoints.test.ts
git commit -m "fix(workstation): invalidate speaker-profiles after enrollment commit; drop dead enrollment hooks (I5, M8)"
```

---

## Task 6: [I3] SSE client lifecycle — backoff, `lastEventId` tracking, final-close

`client.ts:164-213`: a clean server close (`done`) breaks the inner loop and the outer `while` reconnects **immediately**; `failures = 0` on every successful HTTP connect means `maxFailures` is never reached → infinite delay-free reconnect while the detail page is open. `lastEventId` is never updated from the stream, so reconnects replay from the original position. Per **D5**: exponential backoff between reconnects (1s/2s/4s… cap 10s), the client tracks the last seen event id (from SSE `id:` lines, falling back to the payload's `eventId`), and a clean close is final when the consumer reports the stream is finished (`isFinal`). `failures` now counts *event-less* connections only, so a flapping empty endpoint still reaches `onFallback` after 3 tries.

**Files:**
- Modify: `apps/ai-worker-web/src/shared/api/client.ts`
- Modify: `apps/ai-worker-web/src/shared/api/client.test.ts`

**Core change** — replace `EventStreamHandlers` and `subscribeEventStream` wholesale:

```ts
export interface EventStreamHandlers<T = unknown> {
  lastEventId?: string | null;
  onEvent: (event: T) => void;
  onFallback: () => void;
  maxFailures?: number;
  /**
   * Consulted after every connection ends. Return true when the consumer
   * considers the stream finished (e.g. task reached terminal status) —
   * the subscription then ends instead of reconnecting (D5).
   */
  isFinal?: () => boolean;
  /** Test hook: base reconnect delay. Default 1000ms, doubling per attempt, capped at 10s. */
  reconnectBaseDelayMs?: number;
}

export function subscribeEventStream<T = unknown>(
  path: string,
  handlers: EventStreamHandlers<T>,
): EventStreamSubscription {
  const controller = new AbortController();
  const maxFailures = handlers.maxFailures ?? 3;
  const baseDelayMs = handlers.reconnectBaseDelayMs ?? 1_000;
  let failures = 0;
  let attempt = 0;
  let lastEventId: string | null = handlers.lastEventId ?? null;

  const sleep = (ms: number) =>
    new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, ms);
      controller.signal.addEventListener(
        "abort",
        () => {
          clearTimeout(timer);
          resolve();
        },
        { once: true },
      );
    });

  const connect = async () => {
    while (!controller.signal.aborted && failures < maxFailures) {
      if (attempt > 0) {
        await sleep(Math.min(baseDelayMs * 2 ** (attempt - 1), 10_000));
        if (controller.signal.aborted) return;
      }
      attempt += 1;
      let receivedEvent = false;
      try {
        const headers: Record<string, string> = {
          Accept: "text/event-stream",
          "X-Request-Id": uuid(),
          "X-Trace-Id": uuid(),
        };
        const token = authStore.get();
        if (token) headers.Authorization = `Bearer ${token}`;
        if (lastEventId) headers["Last-Event-Id"] = lastEventId;

        const response = await fetch(path, {
          headers,
          credentials: "include",
          signal: controller.signal,
        });
        if (response.status === 401) {
          authStore.clear();
          redirectToLogin();
          throw new ApiError(401, { code: "UNAUTHENTICATED", message: "session expired", retryable: false }, "", "");
        }
        if (!response.ok || !response.body) throw new Error(`SSE ${response.status}`);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (!controller.signal.aborted) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const chunks = buffer.split("\n\n");
          buffer = chunks.pop() ?? "";
          for (const chunk of chunks) {
            const lines = chunk.split("\n");
            const idLine = lines.find((line) => line.startsWith("id:"));
            if (idLine) lastEventId = idLine.slice(3).trim();
            const data = lines
              .filter((line) => line.startsWith("data:"))
              .map((line) => line.slice(5).trimStart())
              .join("\n");
            if (data) {
              const event = JSON.parse(data) as T;
              const eventId = (event as { eventId?: unknown }).eventId;
              if (!idLine && typeof eventId === "string" && eventId) lastEventId = eventId;
              receivedEvent = true;
              handlers.onEvent(event);
            }
          }
        }
        if (controller.signal.aborted) return;
        if (handlers.isFinal?.()) return; // clean close after terminal = final (D5)
      } catch {
        if (controller.signal.aborted) return;
        if (handlers.isFinal?.()) return;
      }
      if (receivedEvent) {
        // Healthy connection: reset the fallback counter, restart backoff at 1× base.
        failures = 0;
        attempt = 1;
      } else {
        failures += 1;
      }
    }
    if (!controller.signal.aborted) handlers.onFallback();
  };

  void connect();
  return { close: () => controller.abort() };
}
```

**Tests** — add to `client.test.ts` (inside the existing `describe`, using its `fetchMock` and bottom-of-file `waitFor` helper; add the `sseResponse` factory next to `waitFor`):

```ts
  it("stops after a clean server close when isFinal reports terminal (I3)", async () => {
    fetchMock.mockImplementation(async () =>
      sseResponse(['id: evt-1\ndata: {"taskId":"t1","status":"SUCCEEDED"}\n\n']));
    const onFallback = vi.fn();
    const events: unknown[] = [];

    subscribeEventStream("/api/processing-tasks/t1/events", {
      onEvent: (event) => events.push(event),
      onFallback,
      isFinal: () => true,
      reconnectBaseDelayMs: 1,
    });

    await waitFor(() => expect(events).toHaveLength(1));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onFallback).not.toHaveBeenCalled();
  });

  it("reconnects with the Last-Event-Id of the last received event (I3)", async () => {
    fetchMock
      .mockImplementationOnce(async () => sseResponse(['id: evt-7\ndata: {"taskId":"t1"}\n\n']))
      .mockImplementation(async () => sseResponse([]));

    const subscription = subscribeEventStream("/api/processing-tasks/t1/events", {
      onEvent: vi.fn(),
      onFallback: vi.fn(),
      isFinal: () => false,
      reconnectBaseDelayMs: 1,
    });

    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2));
    subscription.close();
    const [, secondInit] = fetchMock.mock.calls[1]!;
    expect((secondInit as RequestInit).headers).toMatchObject({ "Last-Event-Id": "evt-7" });
  });

  it("falls back after maxFailures consecutive event-less connections (I3)", async () => {
    fetchMock.mockImplementation(async () => sseResponse([]));
    const onFallback = vi.fn();

    subscribeEventStream("/api/processing-tasks/t1/events", {
      onEvent: vi.fn(),
      onFallback,
      reconnectBaseDelayMs: 1,
    });

    await waitFor(() => expect(onFallback).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
```

```ts
function sseResponse(frames: string[]): Response {
  const stream = new ReadableStream({
    start(controller) {
      for (const frame of frames) controller.enqueue(new TextEncoder().encode(frame));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}
```

- [ ] Add the three tests, run `node scripts/run-vitest.mjs run src/shared/api/client.test.ts` → RED (today: test 1 reconnects forever, test 2 never sends an updated id, test 3 never reaches `onFallback`)
- [ ] Apply the client change, re-run → GREEN, including the pre-existing SSE test (its initial `lastEventId: "task1:1"` seeds the internal tracker, same first-request header)
- [ ] `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/shared/api/client.ts apps/ai-worker-web/src/shared/api/client.test.ts
git commit -m "fix(workstation): SSE reconnect backoff, stream-tracked Last-Event-Id, final clean close (I3)"
```

---

## Task 7: [I3 + I4] MeetingDetailPage stream/poll lifecycle

`MeetingDetailPage.tsx:67-98`: SSE only opens when the **initial** aggregate already has a `taskId`, and polling only starts via SSE `onFallback` — landing after audio-upload-complete but before Java's outbox creates the ProcessingTask leaves all steps PENDING forever (I4). `handleEvent` (lines 124-126) detects terminal status but never closes the stream (I3, page half). Per **D5/D6**: while `latestTask == null`, poll the aggregate every 3s; attach SSE once a `taskId` appears; on a terminal event close the stream and stop all timers; pass `isFinal` so a clean server close right after the terminal event doesn't reconnect; stop everything on unmount.

**Files:**
- Modify: `apps/ai-worker-web/src/pages/MeetingDetailPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/MeetingDetailPage.test.tsx`

**Core change** — replace the main `useEffect` (lines 67-98) and `openTaskEvents` (lines 110-136):

```tsx
  useEffect(() => {
    if (!meetingId) return;
    let cancelled = false;
    let eventStream: EventStreamSubscription | null = null;
    let fallbackPollTimer: ReturnType<typeof setInterval> | null = null;
    let bootstrapPollTimer: ReturnType<typeof setInterval> | null = null;
    let terminal = false;

    const stopTimers = () => {
      if (fallbackPollTimer) {
        clearInterval(fallbackPollTimer);
        fallbackPollTimer = null;
      }
      if (bootstrapPollTimer) {
        clearInterval(bootstrapPollTimer);
        bootstrapPollTimer = null;
      }
    };

    const handleTerminal = () => {
      terminal = true;
      eventStream?.close();
      eventStream = null;
      stopTimers();
    };

    const load = async () => {
      try {
        const data = await getMeetingAggregate(meetingId);
        if (cancelled) return;
        setAggregate(data);
        seedSteps(data.latestTask?.steps);
        const latestTask = data.latestTask;
        if (latestTask && TERMINAL_STATUSES.includes(latestTask.status)) {
          handleTerminal();
          return;
        }
        if (latestTask?.taskId) {
          if (bootstrapPollTimer) {
            clearInterval(bootstrapPollTimer);
            bootstrapPollTimer = null;
          }
          if (!eventStream && !terminal) {
            eventStream = openTaskEvents(latestTask.taskId, {
              isFinal: () => terminal,
              onTerminal: handleTerminal,
              onFallback: () => {
                if (!fallbackPollTimer && !terminal && !cancelled) {
                  fallbackPollTimer = setInterval(() => void load(), 5000);
                }
              },
            });
          }
        } else if (!bootstrapPollTimer && !terminal) {
          // No ProcessingTask yet — Java creates it asynchronously (outbox)
          // after audio upload completes. Poll the aggregate until it appears (D6).
          bootstrapPollTimer = setInterval(() => void load(), 3000);
        }
      } catch (e) {
        if (!cancelled) setError(formatError(e));
      }
    };

    void load();
    return () => {
      cancelled = true;
      eventStream?.close();
      stopTimers();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- openTaskEvents/seedSteps are stable per mount
  }, [meetingId]);
```

```tsx
  const openTaskEvents = (
    taskId: string,
    opts: { isFinal: () => boolean; onTerminal: () => void; onFallback: () => void },
  ) => {
    const handleEvent = (payload: TaskEventDTO) => {
      try {
        if (payload.steps?.length) {
          seedSteps(payload.steps);
        } else if (payload.stepName) {
          seedSteps([{
            stepName: payload.stepName,
            status: payload.status ?? "RUNNING",
            progress: payload.progress ?? 0,
            retryable: payload.retryable,
            errorCode: payload.errorCode,
          }]);
        }
        if (payload.status && TERMINAL_STATUSES.includes(payload.status as ProcessingTaskStatus)) {
          opts.onTerminal();
          void refreshAggregate();
        }
      } catch (e) {
        setError(formatError(e));
      }
    };

    return subscribeEventStream<TaskEventDTO>(processingTaskEventsUrl(taskId), {
      onEvent: handleEvent,
      onFallback: opts.onFallback,
      isFinal: opts.isFinal,
    });
  };
```

(Behavior note: an aggregate whose task is already terminal no longer opens a pointless SSE connection — existing tests using `succeededTask()` make no SSE assertions, verified.)

**Tests** — add to `MeetingDetailPage.test.tsx`:

```tsx
  it("closes the SSE stream when a terminal status event arrives (I3)", async () => {
    renderPage();
    await waitFor(() => expect(taskEventHandler).not.toBeNull());

    act(() => {
      taskEventHandler?.({ taskId: "task1", status: "SUCCEEDED" });
    });

    await waitFor(() => expect(streamClose).toHaveBeenCalled());
  });

  it("polls the aggregate while no task exists and attaches SSE once it appears (I4)", async () => {
    vi.useFakeTimers();
    try {
      const endpoints = await import("@/shared/api/endpoints");
      vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce(
        defaultAggregate({ latestTask: null, speakers: [], minutes: null }),
      );

      renderPage();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(taskEventHandler).toBeNull(); // no taskId yet → no SSE

      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000); // bootstrap poll fires; default mock now returns a task
      });

      expect(endpoints.getMeetingAggregate).toHaveBeenCalledTimes(2);
      expect(taskEventHandler).not.toBeNull(); // SSE attached after the task appeared
    } finally {
      vi.useRealTimers();
    }
  });
```

- [ ] Add the tests, run `node scripts/run-vitest.mjs run src/pages/MeetingDetailPage.test.tsx` → RED (`streamClose` never called; second `getMeetingAggregate` never happens)
- [ ] Apply the change, re-run → GREEN including all 11 pre-existing tests
- [ ] `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/pages/MeetingDetailPage.tsx apps/ai-worker-web/src/pages/MeetingDetailPage.test.tsx
git commit -m "fix(workstation): close SSE on terminal status and bootstrap-poll aggregate until task exists (I3, I4)"
```

---

## Task 8: [I2 + I5(meetings) + M2] Resumable meeting creation

`NewMeetingPage.tsx:155-192`: `startProcessing` resets `createdMeetingId` to `null` on every click and re-runs createMeeting → glossary → attach → audio upload from scratch — a failure after `createMeeting` leaves an audio-less meeting, and re-clicking creates a duplicate. Per **D4**: keep `createdMeetingId` in React state across failures, track completed steps in a ref, resume from the first failed step, and derive step idempotency keys from `meetingId` + step name (audio init additionally keyed by `fileSha256` so swapping the file between retries can't collide with the old body hash). This task also: invalidates `["admin", "meetings"]` after creation (the meetings half of **I5**, key matches `useAdminMeetingsQuery`), and stops painting user-initiated cancels as red failures (**M2** — `startProcessing`'s catch now filters `isUploadAborted` exactly like `handleDocumentFile:145` already does).

**Files:**
- Modify: `apps/ai-worker-web/src/pages/NewMeetingPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/NewMeetingPage.test.tsx`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts` + `endpoints.test.ts`

**Core change 1** — derived idempotency keys in `endpoints.ts`:

```ts
export const updateMeetingGlossary = (meetingId: string, terms: GlossaryTermDTO[]) =>
  apiCall<{ meetingId: string; terms: GlossaryTermDTO[] }>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/glossary`,
    { method: "PATCH", body: { terms }, idempotencyKey: `${meetingId}:glossary` },
  );

export const attachMeetingDocument = (meetingId: string, body: { documentId: string; role: "REFERENCE" | "ATTACHMENT" }) =>
  apiCall<MeetingDocumentItemDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/documents:attach`,
    { method: "POST", body, idempotencyKey: `${meetingId}:attach:${body.documentId}` },
  );
```

and on the three audio-upload helpers, add to their existing options objects:

```ts
// initAudioUpload:        idempotencyKey: `${meetingId}:audio:${req.fileSha256}`,
// createAudioUploadPart:  idempotencyKey: `${uploadId}:part:${req.partNumber}`,
// completeAudioUpload:    idempotencyKey: `${uploadId}:complete`,
```

(`createMeeting` keeps the auto-UUID: it is a genuine fire-once write, guarded below by the resume logic — it is simply never called twice per form.)

**Core change 2** — `NewMeetingPage.tsx`:

```tsx
import { useQueryClient } from "@tanstack/react-query";
// inside the component:
  const queryClient = useQueryClient();
  const completedSteps = useRef({ glossaryDone: false, attachedDocumentIds: new Set<string>() });
```

Replace `startProcessing` (lines 155-192):

```tsx
  const startProcessing = async () => {
    if (!canStart || !audioFile) return;
    setBusy(true);
    setError(null);
    try {
      let meetingId = createdMeetingId;
      if (!meetingId) {
        const meeting = await createMeeting({
          title: title.trim(),
          language,
          participants: selectedParticipants.map((participant) => ({
            personId: participant.personId,
            displayName: participant.displayName,
            role: participant.role,
          })),
        });
        meetingId = meeting.meetingId;
        setCreatedMeetingId(meetingId);
        void queryClient.invalidateQueries({ queryKey: ["admin", "meetings"] });
      }
      const mid = meetingId; // narrowed string for the uploader closures
      if (terms.length > 0 && !completedSteps.current.glossaryDone) {
        await updateMeetingGlossary(mid, terms);
        completedSteps.current.glossaryDone = true;
      }
      for (const document of selectedDocuments) {
        if (completedSteps.current.attachedDocumentIds.has(document.documentId)) continue;
        await attachMeetingDocument(mid, { documentId: document.documentId, role: "REFERENCE" });
        completedSteps.current.attachedDocumentIds.add(document.documentId);
      }
      const audioUploader = new MultipartUploader({
        file: audioFile,
        init: (req) => initAudioUpload(mid, req),
        createPart: (uploadId, req) => createAudioUploadPart(mid, uploadId, req),
        complete: (uploadId, req) => completeAudioUpload(mid, uploadId, req),
        abort: (uploadId) => abortAudioUpload(mid, uploadId),
        onProgress: setAudioProgress,
      });
      activeAudioUploader.current = audioUploader;
      await audioUploader.upload();
      navigate(`/meetings/${mid}`);
    } catch (e) {
      if (!isUploadAborted(e)) setError(formatError(e)); // M2: user cancel is not a failure
    } finally {
      setBusy(false);
      activeAudioUploader.current = null;
    }
  };
```

(Known limitation, acceptable for an operator tool: if the operator *edits* terms after the glossary step already succeeded, the retry skips re-sending them — the recovery link to the meeting detail page is the editing surface at that point.)

**Test wiring** — `useQueryClient` needs a provider. Add a helper to `NewMeetingPage.test.tsx` and convert every inline `render(<MemoryRouter…>…)` call to it (mechanical, 8 call sites):

```tsx
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

function renderNewMeetingPage(initialEntries: string[] = ["/meetings/new"]) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/meetings/new" element={<NewMeetingPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
```

**Test 1** — resume semantics (`NewMeetingPage.test.tsx`):

```tsx
  it("resumes from the failed step instead of re-creating the meeting on retry (I2)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.initAudioUpload).mockRejectedValueOnce(new ApiError(
      503,
      { code: "AUDIO_UPLOAD_INIT_FAILED", message: "audio upload unavailable", retryable: true },
      "r",
      "t",
    ));
    renderNewMeetingPage();

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    const termInput = screen.getByPlaceholderText(/按 Enter 添加术语/);
    fireEvent.change(termInput, { target: { value: "LLM" } });
    fireEvent.keyDown(termInput, { key: "Enter" });
    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });

    fireEvent.click(screen.getByTestId("start-processing"));
    expect(await screen.findByRole("alert")).toHaveTextContent("AUDIO_UPLOAD_INIT_FAILED");

    fireEvent.click(screen.getByTestId("start-processing"));
    await waitFor(() => expect(navigateTarget).toHaveBeenCalledWith("/meetings/m1"));

    expect(endpoints.createMeeting).toHaveBeenCalledTimes(1);          // not re-created
    expect(endpoints.updateMeetingGlossary).toHaveBeenCalledTimes(1);  // step not replayed
  });
```

**Test 2** — derived keys (`endpoints.test.ts`; add `updateMeetingGlossary`, `attachMeetingDocument` to the import list):

```ts
  it("derives meeting-step idempotency keys from business keys (I2/D3)", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ meetingId: "m1", terms: [] }))
      .mockResolvedValueOnce(jsonResponse({ id: "att1", documentId: "d1", title: null, role: "REFERENCE", attachedBy: null, attachedAt: "" }))
      .mockResolvedValueOnce(jsonResponse({ uploadId: "u1", parts: [] }))
      .mockResolvedValueOnce(jsonResponse({ partNumber: 1, uploadUrl: "https://u/1", expiresAt: "", headers: {} }))
      .mockResolvedValueOnce(jsonResponse({ uploadId: "u1", uploadStatus: "COMPLETED", parts: [] }));

    await updateMeetingGlossary("m1", []);
    await attachMeetingDocument("m1", { documentId: "d1", role: "REFERENCE" });
    await initAudioUpload("m1", { fileName: "a.mp3", contentType: "audio/mpeg", fileSizeBytes: 1, fileSha256: "abc" });
    await createAudioUploadPart("m1", "u1", { partNumber: 1, sizeBytes: 1, partSha256: "b" });
    await completeAudioUpload("m1", "u1", { fileSha256: "abc", parts: [{ partNumber: 1, partSha256: "b", etag: "e" }] });

    const keys = fetchMock.mock.calls.map(
      (call) => ((call[1] as RequestInit).headers as Record<string, string>)["Idempotency-Key"],
    );
    expect(keys).toEqual(["m1:glossary", "m1:attach:d1", "m1:audio:abc", "u1:part:1", "u1:complete"]);
  });
```

- [ ] Add both tests + the render helper conversion, run `node scripts/run-vitest.mjs run src/pages/NewMeetingPage.test.tsx src/shared/api/endpoints.test.ts` → RED (`createMeeting` called twice; keys are UUIDs)
- [ ] Apply changes, re-run → GREEN, then `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/pages/NewMeetingPage.tsx apps/ai-worker-web/src/pages/NewMeetingPage.test.tsx apps/ai-worker-web/src/shared/api/endpoints.ts apps/ai-worker-web/src/shared/api/endpoints.test.ts
git commit -m "fix(workstation): resumable meeting creation with business-key idempotency; invalidate meetings list (I2, I5, M2)"
```

---

## Task 9: [I6 + I7 + I8] Detail-page interaction fixes

Three independent defects in `MeetingDetailPage.tsx`, fixed together because they share the file and test harness:

- **I6 / D8** (`:145-166`): export polling exits silently after 30×1s (button re-enables, pill stuck QUEUED/RUNNING) and keeps polling + `setExportJob` after navigation away. Fix: `AbortController` tied to unmount, explicit timeout error, no state writes after abort.
- **I7 / D9** (`:342`): only the clicked candidate is disabled during a confirm; competing 认定 buttons and 驳回 stay live → racing conflicting writes. Fix: per-**label** busy state disabling every decision button for that label.
- **I8 / D10** (`:169, 189`): `handleConfirmCandidate` / `handleAddParticipant` silently return when `transcriptVersion` is missing. Fix: confirm shows a disabled state + explicit message (the field is required by `confirmSpeaker`'s body); add-participant simply omits `expectedVersion` (optional in `UpdateMeetingRequest` — verified in `endpoints.ts:80`).

**Files:**
- Modify: `apps/ai-worker-web/src/pages/MeetingDetailPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/MeetingDetailPage.test.tsx`
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts` (`pollExport` gains a signal)

**Core changes:**

1. `endpoints.ts` — abortable poll:

```ts
export const pollExport = (meetingId: string, exportId: string, opts: { signal?: AbortSignal } = {}) =>
  apiCall<ExportJobDTO>(
    `${API}/meetings/${encodeURIComponent(meetingId)}/exports/${encodeURIComponent(exportId)}`,
    { signal: opts.signal },
  );
```

2. `MeetingDetailPage.tsx` — add `useRef` to the React import; replace export/confirm/add-participant state and handlers:

```tsx
  const [busySpeakerLabel, setBusySpeakerLabel] = useState<string | null>(null); // replaces confirmingSpeaker
  const exportAbortRef = useRef<AbortController | null>(null);

  useEffect(() => () => exportAbortRef.current?.abort(), []);

  const transcriptVersionMissing = !!meeting && typeof meeting.transcriptVersion !== "number";
  const decisionPending = (label: string) => busySpeakerLabel === label || rejectingSpeaker === label;

  const handleExport = async () => {
    if (!meetingId) return;
    exportAbortRef.current?.abort();
    const ac = new AbortController();
    exportAbortRef.current = ac;
    setBusyExport(true);
    setError(null);
    try {
      const created = await createExport(meetingId, "DOCX");
      if (ac.signal.aborted) return;
      setExportJob(created);
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const polled = await pollExport(meetingId, created.exportId, { signal: ac.signal });
        if (ac.signal.aborted) return;
        setExportJob(polled);
        if (polled.status === "SUCCEEDED" && polled.downloadUrl) return;
        if (["FAILED", "CANCELLED", "REVOKED"].includes(polled.status)) {
          throw new Error(`导出失败: ${polled.status}`);
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
        if (ac.signal.aborted) return;
      }
      throw new Error("导出超时：30 秒内未完成，任务可能仍在执行。请稍后重试，或刷新页面查看导出状态。");
    } catch (e) {
      if (!ac.signal.aborted) setError(formatError(e));
    } finally {
      if (!ac.signal.aborted) setBusyExport(false);
    }
  };

  const handleConfirmCandidate = async (speaker: MeetingSpeakerDTO, candidate: SpeakerCandidateDTO) => {
    if (!meetingId) return;
    if (typeof meeting?.transcriptVersion !== "number") {
      setError("转写版本缺失，无法认定说话人——请刷新页面后重试。");
      return;
    }
    const label = getSpeakerLabel(speaker);
    setBusySpeakerLabel(label);
    setError(null);
    try {
      await confirmSpeaker(meetingId, label, {
        personId: candidate.personId,
        speakerProfileId: candidate.speakerProfileId,
        expectedTranscriptVersion: meeting.transcriptVersion,
      });
      await refreshAggregate();
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusySpeakerLabel(null);
    }
  };

  const handleAddParticipant = async (person: PersonDTO) => {
    if (!meetingId || !meeting) return;
    if (!person.personId || participants.some((participant) => participant.personId === person.personId)) return;
    const nextParticipants: MeetingParticipantDTO[] = [
      ...participants,
      { personId: person.personId, displayName: person.displayName, role: DEFAULT_PARTICIPANT_ROLE },
    ];
    setAddingParticipant(person.personId);
    setError(null);
    try {
      await updateMeeting(meetingId, {
        participants: nextParticipants,
        // expectedVersion is optional in UpdateMeetingRequest — omit instead of blocking (D10)
        ...(typeof meeting.transcriptVersion === "number"
          ? { expectedVersion: meeting.transcriptVersion }
          : {}),
      });
      personSearch.reset();
      await refreshAggregate();
    } catch (e) {
      setError(formatError(e));
    } finally {
      setAddingParticipant(null);
    }
  };
```

3. JSX in the speakers section: add the hint after the `<h2>` and rewire the decision buttons:

```tsx
            {transcriptVersionMissing ? (
              <p className="error" role="note">转写版本缺失，暂时无法执行说话人认定/驳回，请刷新页面。</p>
            ) : null}
```

```tsx
                          {speaker.candidates?.map((candidate) => (
                            <button
                              key={`${candidate.personId}:${candidate.speakerProfileId}`}
                              className="button button--secondary"
                              type="button"
                              disabled={decisionPending(label) || transcriptVersionMissing}
                              onClick={() => void handleConfirmCandidate(speaker, candidate)}
                            >
                              认定 {candidate.displayName} {candidate.confidence.toFixed(2)}
                            </button>
                          ))}
                          {canRejectSpeaker(speaker) ? (
                            <button
                              className="button button--ghost"
                              type="button"
                              disabled={decisionPending(label)}
                              onClick={() => {
                                setRejectError(null);
                                setPendingRejectSpeaker(speaker);
                              }}
                            >
                              驳回 {label}
                            </button>
                          ) : null}
```

**Tests** — add to `MeetingDetailPage.test.tsx`:

```tsx
  it("surfaces an explicit timeout error when export polling exhausts (I6)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.pollExport).mockResolvedValue({ exportId: "exp1", status: "RUNNING", format: "DOCX" });
    vi.useFakeTimers();
    try {
      renderPage();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });

      fireEvent.click(screen.getByTestId("export-docx"));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(31_000);
      });

      expect(screen.getByRole("alert")).toHaveTextContent(/导出超时/);
      expect(screen.getByTestId("export-docx")).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("disables every decision button for a label while one decision is in flight (I7)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce(defaultAggregate({
      speakers: [candidateSpeaker()],
    }));
    let resolveConfirm: () => void = () => undefined;
    vi.mocked(endpoints.confirmSpeaker).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveConfirm = () => resolve({
          speakerLabel: "SPEAKER_01", displayName: "李四", personId: "p1",
          speakerProfileId: "sp1", confirmationStatus: "MANUALLY_CONFIRMED", candidates: [],
        });
      }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "认定 李四 0.91" }));

    expect(screen.getByRole("button", { name: "认定 李四 0.91" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "认定 王五 0.72" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "驳回 SPEAKER_01" })).toBeDisabled();
    act(() => resolveConfirm());
  });

  it("shows a disabled state with explanation when transcriptVersion is missing, but still adds participants (I8)", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    const aggregate = defaultAggregate({ speakers: [candidateSpeaker()] });
    aggregate.meeting = { ...aggregate.meeting!, transcriptVersion: undefined };
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce(aggregate);
    renderPage();

    expect(await screen.findByRole("button", { name: "认定 李四 0.91" })).toBeDisabled();
    expect(screen.getByText(/转写版本缺失/)).toBeInTheDocument();

    const participants = screen.getByRole("region", { name: "参会人" });
    fireEvent.change(within(participants).getByLabelText("搜索人员"), { target: { value: "王" } });
    fireEvent.click(await within(participants).findByRole("button", { name: "添加 王五" }));

    await waitFor(() => expect(endpoints.updateMeeting).toHaveBeenCalledWith("m1", {
      participants: [
        { personId: "p1", displayName: "李四", role: "PARTICIPANT" },
        { personId: "p2", displayName: "王五", role: "PARTICIPANT" },
      ],
      // no expectedVersion key
    }));
  });
```

- [ ] Add the tests, run `node scripts/run-vitest.mjs run src/pages/MeetingDetailPage.test.tsx` → RED (no timeout alert; sibling buttons enabled; add-participant silently no-ops)
- [ ] Apply changes, re-run → GREEN including all pre-existing tests (the export-success and conflict tests are unaffected; the `confirmingSpeaker` rename has no other references)
- [ ] `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/pages/MeetingDetailPage.tsx apps/ai-worker-web/src/pages/MeetingDetailPage.test.tsx apps/ai-worker-web/src/shared/api/endpoints.ts
git commit -m "fix(workstation): export timeout/abort handling, per-label speaker decision lock, no silent version no-ops (I6, I7, I8)"
```

---

## Task 10: [I9] Chunked hashing + ETag fail-fast in MultipartUploader

`MultipartUploader.ts:65` buffers the entire recording via `sha256(await readBlobAsArrayBuffer(this.opts.file))` — multi-hundred-MiB audio freezes the tab. `normalizeEtag` (`:146, 172-174`) silently returns `""` when the presigned PUT response hides the `ETag` header (MinIO/TOS bucket CORS missing `Access-Control-Expose-Headers: ETag`), so `complete` is called with empty etags and fails with a confusing server error. Per **D11**: port meeting-web's streaming hasher (`apps/meeting-web/src/shared/utils/sha256-stream.ts`, 4 MiB chunks) plus its test, and fail fast client-side on a missing ETag with the CORS cause in the message.

**Files:**
- Create: `apps/ai-worker-web/src/shared/utils/sha256-stream.ts` — **byte-for-byte copy** of `apps/meeting-web/src/shared/utils/sha256-stream.ts` (exports `Sha256`, `toHex`, `sha256Hex(blob, chunkSize = 4 MiB)`; uses BigInt — fine under this workspace's `target: ES2022`)
- Create: `apps/ai-worker-web/src/shared/utils/sha256-stream.test.ts` — copy of `apps/meeting-web/src/shared/utils/__tests__/sha256-stream.test.ts`, with the import path changed to `./sha256-stream` (this workspace uses sibling `.test.ts` files, not `__tests__/`)
- Modify: `apps/ai-worker-web/src/shared/upload/MultipartUploader.ts`
- Modify: `apps/ai-worker-web/src/shared/upload/MultipartUploader.test.ts`

**Core change** — `MultipartUploader.ts`:

```ts
import { sha256Hex } from "@/shared/utils/sha256-stream";
```

1. Line 65: `const fileSha256 = await sha256Hex(this.opts.file);`
2. Line 95: `const partSha256 = await sha256Hex(blob);`
3. Delete the `sha256()` and `readBlobAsArrayBuffer()` helpers (lines 184-205) and the `normalizeEtag()` helper (lines 172-174).
4. Pass the part number into the PUT and fail fast:

```ts
        const etag = await this.putWithRetry(uploadUrl, blob, part.headers ?? {}, partNumber);
```

```ts
  private async putWithRetry(url: string, blob: Blob, headers: Record<string, string>, partNumber: number): Promise<string> {
    // …unchanged retry loop, except the success branch:
      if (response.ok) return requireEtag(response.headers.get("etag"), partNumber);
    // …
  }
```

```ts
function requireEtag(etag: string | null, partNumber: number): string {
  const normalized = (etag ?? "").replace(/^"|"$/g, "").trim();
  if (!normalized) {
    throw new MultipartUploadError(
      "ETAG_MISSING",
      `对象存储未返回 part ${partNumber} 的 ETag 响应头。` +
        "通常原因：存储桶 CORS 未配置 Access-Control-Expose-Headers: ETag（MinIO/TOS 常见），" +
        "浏览器因此读不到该响应头。请修正 CORS 后重试上传。",
    );
  }
  return normalized;
}
```

**Tests** — add to `MultipartUploader.test.ts`:

```ts
  it("streams the file hash instead of reading the whole file into memory (I9)", async () => {
    const bigFile = file(4);
    // Whole-file reads are forbidden; chunk slices (fresh Blobs) still work.
    Object.defineProperty(bigFile, "arrayBuffer", {
      value: () => {
        throw new Error("whole-file arrayBuffer read attempted");
      },
    });
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const complete = vi.fn(async () => ({ fileId: "f1", sha256: "x", sizeBytes: 4, contentType: "application/pdf" }));
    const uploader = new MultipartUploader({ file: bigFile, partSizeBytes: PART, init, createPart: vi.fn(), complete, abort: vi.fn() });

    await expect(uploader.upload()).resolves.toMatchObject({ fileId: "f1" });
    expect(init).toHaveBeenCalledWith(expect.objectContaining({
      fileSha256: expect.stringMatching(/^[0-9a-f]{64}$/),
    }));
  });

  it("fails fast with a CORS hint when the presigned PUT hides the ETag header (I9)", async () => {
    globalThis.fetch = vi.fn(async () => new Response("", { status: 200 })) as unknown as typeof fetch; // no etag header
    const init = vi.fn(async () => ({
      uploadId: "u1",
      parts: [{ partNumber: 1, uploadUrl: "https://presign/1", expiresAt: "", headers: {} }],
    }));
    const complete = vi.fn();
    const uploader = new MultipartUploader({ file: file(4), partSizeBytes: PART, init, createPart: vi.fn(), complete, abort: vi.fn() });

    await expect(uploader.upload()).rejects.toMatchObject({
      code: "ETAG_MISSING",
      message: expect.stringContaining("Access-Control-Expose-Headers"),
    });
    expect(complete).not.toHaveBeenCalled();
  });
```

- [ ] Copy the two files from meeting-web (adjust the test's import path), run `node scripts/run-vitest.mjs run src/shared/utils/sha256-stream.test.ts` → GREEN (correctness baseline against FIPS vectors + `crypto.subtle`)
- [ ] Add the two uploader tests, run `node scripts/run-vitest.mjs run src/shared/upload/MultipartUploader.test.ts` → RED (whole-file read throws; empty-etag case resolves and calls `complete`)
- [ ] Apply the uploader change, re-run → GREEN including the 10 pre-existing uploader tests (they all serve `etag: '"etag-1"'`)
- [ ] `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/shared/utils/sha256-stream.ts apps/ai-worker-web/src/shared/utils/sha256-stream.test.ts apps/ai-worker-web/src/shared/upload/MultipartUploader.ts apps/ai-worker-web/src/shared/upload/MultipartUploader.test.ts
git commit -m "fix(workstation): chunked streaming SHA-256 for uploads and fail-fast on missing ETag (I9)"
```

---

## Task 11: [I10] Remove prefilled admin credentials

`LoginPage.tsx:40-41` ships `useState("admin")` / `useState("admin123")` in the production bundle. Per **D12**: initial state becomes empty strings. Verified: neither Playwright spec uses the login form (both use `?playwright-skip-auth=1`, see M4), so no e2e fixture work is required *in this task* — if a future e2e drives the form, credentials come from Playwright env (`process.env.E2E_ADMIN_USER` etc. in the spec), never from component defaults. `LoginPage.test.tsx:49-52` currently depends on the prefill and must type credentials explicitly.

**Files:**
- Modify: `apps/ai-worker-web/src/pages/LoginPage.tsx`
- Modify: `apps/ai-worker-web/src/pages/LoginPage.test.tsx`

**Core change:**

```tsx
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
```

**Test** — in `LoginPage.test.tsx`, update the first test to fill the form before submitting:

```tsx
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "admin" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "admin123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
```

and add:

```tsx
  it("starts with empty credential fields — no defaults in the bundle (I10)", () => {
    render(
      <MemoryRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByLabelText("用户名")).toHaveValue("");
    expect(screen.getByLabelText("密码")).toHaveValue("");
  });
```

- [ ] Add/adjust tests, run `node scripts/run-vitest.mjs run src/pages/LoginPage.test.tsx` → new test RED
- [ ] Apply the change → GREEN; `npm test && npm run type-check && npm run lint`
- [ ] Commit:

```bash
git add apps/ai-worker-web/src/pages/LoginPage.tsx apps/ai-worker-web/src/pages/LoginPage.test.tsx
git commit -m "fix(workstation): remove hardcoded default admin credentials from LoginPage (I10)"
```

---

## Task 12: Minor triage table (M1–M12)

Not scheduled as individual tasks. Fix opportunistically or in a follow-up batch; two are already closed by tasks above. Paths corrected to the real tree.

| ID | Location | Problem | One-line fix |
|----|----------|---------|--------------|
| M1 | `src/pages/MeetingDetailPage.tsx:459-461` | `progress <= 1 ? progress*100 : progress` maps a real 1% to 100%; applied twice (seed via `normalizeStep` + render at `:305`). Contract `progress` is integer 0–100. | Replace `normalizeProgress` with `Math.max(0, Math.min(100, Math.round(progress)))` and apply it in exactly one place (`normalizeStep`). |
| M2 | `src/pages/NewMeetingPage.tsx:186-188` | User-initiated cancel rendered as red `UPLOAD_ABORTED` failure. | **Closed by Task 8** (`catch` now filters `isUploadAborted`). |
| M3 | `src/pages/NewMeetingPage.tsx:114` | Upload-row id `name-lastModified-size` → duplicate React keys when the same file is picked twice. | Append a monotonic counter: `const id = \`${file.name}-${file.lastModified}-${file.size}-${uploadSeq.current++}\`` (a `useRef(0)`). |
| M4 | `src/shared/auth/useAuth.ts:15` | `playwright-skip-auth` URL-substring auth bypass ships in the prod bundle. | Gate on `import.meta.env.MODE !== "production"`; both e2e specs (`e2e/*.spec.ts`) depend on it, so land together with an e2e fixture that stubs `/api/auth/login` + fragment token instead. |
| M5 | `src/shared/auth/useAuth.ts:8-19` | Token read then subscribe (non-atomic in principle; benign today since both happen in one synchronous effect body). | Reorder: `const unsubscribe = authStore.subscribe(setToken)` first, then `consumeFragmentToken()` / read / redirect; return `unsubscribe`. |
| M6 | `src/App.tsx:49-58` | No catch-all route — unknown paths render a blank main area. | Add `<Route path="*" element={<Navigate to="/meetings" replace />} />` (import `Navigate` from react-router-dom). |
| M7 | `src/pages/PeoplePage.tsx:25-27, 39-43` | URL-sync effect snaps whitespace-only input back to `""` mid-typing (URL only updates for trimmed-non-empty). | Compare trimmed values in the effect: `if (queryFromUrl !== query.trim()) setQuery(queryFromUrl)` — or only sync URL→state on mount/popstate. |
| M8 | `src/features/meetings/queries.ts:27-44`, `endpoints.ts:140-141` | Dead enrollment mutation hooks + `getProcessingTask` (EnrollmentPage reimplements with local state — root cause of I5). | **Closed by Task 5** (hooks + endpoint deleted; deliberate non-adoption documented there). |
| M9 | `src/shared/api/client.ts:77` | `DELETE` excluded from `Idempotency-Key` injection; latent (no DELETE endpoints today). | Change the guard to `if (method !== "GET")`. |
| M10 | `src/pages/EnrollmentPage.tsx` (step 2) | No audio playback before commit — operator can't verify they picked the right recording. | Render `<audio controls src={objectUrl} />` from `URL.createObjectURL(file)` when a file is selected; revoke the URL on file change/unmount. |
| M11 | `src/shared/components/PersonCreateModal.tsx:71`, `src/pages/MeetingDetailPage.tsx` reject modal (~`:403` pre-task numbering), `src/pages/SpeakerProfilesPage.tsx:127` | Modals lack Escape/backdrop dismissal and focus trap. | Extract a shared `<Modal onClose>` wrapper with `keydown` Escape handler, backdrop `onClick={onClose}`, and a focus trap (e.g. focus sentinel divs); adopt in all three. |
| M12 | `src/shared/auth/store.ts:53-54` | Comment claims the workstation Ingress routes only `/admin` + `/workstation`; `infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml:151` also routes `/api`. | Update the comment to "…routes /admin, /workstation **and /api** (so the SPA can reach Java SSE directly)". |

---

## Final verification

- [ ] `cd apps/ai-worker-web && npm test` — full vitest suite green
- [ ] `cd apps/ai-worker-web && npm run type-check && npm run lint && npm run build` — CI gates + bundle build green
- [ ] `cd apps/ai-worker-web && npm run e2e` — both Playwright specs still pass (no task changed the flows they exercise; the enrollment spec's commit route now receives a `{personId}` body it ignores)
- [ ] `cd apps/ai-worker && uv run pytest tests/admin/ -q && uv run pyright ai_worker/` — BFF suite + types green
- [ ] `git log --oneline master..` shows ~11 commits, prefixes `fix(workstation):` / `fix(worker):` as specified
- [ ] Update `todo.md` ledger entry for "review remediation P4 (ai-worker-web)" if the volume is tracked there
