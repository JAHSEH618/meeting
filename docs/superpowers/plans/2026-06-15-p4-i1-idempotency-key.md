# P4 I1: commitEnrollment Idempotency Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use sessionId as idempotency key for commitEnrollment to enable safe retries

**Architecture:** Currently, `apiCall` generates a random UUID for each request's idempotency key. This breaks retry safety because each retry gets a new key. The BFF already has a 5-step orchestration idempotency design that expects stable keys. By passing `sessionId` as the idempotency key for commitEnrollment, retries will use the same key and be properly deduplicated by the backend.

**Tech Stack:** 
- Frontend: React 18, TypeScript strict (apps/ai-worker-web)
- API Client: Custom fetch wrapper with idempotency support
- Testing: Vitest

**Current Flow:**
```
commitEnrollment(sessionId, personId) 
  → apiCall(...) 
  → headers["Idempotency-Key"] = uuid() // ❌ New random key each time
```

**Target Flow:**
```
commitEnrollment(sessionId, personId) 
  → apiCall(..., { idempotencyKey: sessionId }) 
  → headers["Idempotency-Key"] = sessionId // ✅ Stable key for retries
```

---

## Task 1: Update commitEnrollment to Pass Idempotency Key

**Files:**
- Modify: `apps/ai-worker-web/src/shared/api/endpoints.ts:48-52`
- Test: `apps/ai-worker-web/src/shared/api/endpoints.test.ts` (create if not exists)

### Background

The `commitEnrollment` function currently doesn't pass an idempotency key, causing `apiCall` to generate a random UUID. We need to explicitly pass `sessionId` as the idempotency key.

- [ ] **Step 1: Write failing test for idempotency key**

Location: `apps/ai-worker-web/src/shared/api/endpoints.test.ts` (create file)

```typescript
import { describe, it, expect, vi, beforeEach } from "vitest";
import { commitEnrollment } from "./endpoints";
import * as client from "./client";

describe("commitEnrollment", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("uses sessionId as idempotency key", async () => {
    const apiCallSpy = vi.spyOn(client, "apiCall").mockResolvedValue({
      sessionId: "session_001",
      state: "COMMITTED",
      personId: "person_001",
      qualityScore: 0.85,
    });

    await commitEnrollment("session_001", "person_001");

    expect(apiCallSpy).toHaveBeenCalledWith(
      "/admin/enrollment/sessions/session_001/commit",
      expect.objectContaining({
        method: "POST",
        body: { personId: "person_001" },
        idempotencyKey: "session_001",
      })
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd apps/ai-worker-web
npm test -- endpoints.test.ts
```

Expected: Test fails - `idempotencyKey` not passed

- [ ] **Step 3: Update commitEnrollment to pass idempotency key**

Location: `apps/ai-worker-web/src/shared/api/endpoints.ts`

Find this code (lines 48-52):

```typescript
export const commitEnrollment = (sessionId: string, personId: string | null) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/commit`,
    { method: "POST", body: { personId } },
  );
```

Replace with:

```typescript
export const commitEnrollment = (sessionId: string, personId: string | null) =>
  apiCall<EnrollmentSessionDTO>(
    `${API}/enrollment/sessions/${encodeURIComponent(sessionId)}/commit`,
    { method: "POST", body: { personId }, idempotencyKey: sessionId },
  );
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test -- endpoints.test.ts
```

Expected: Test passes

- [ ] **Step 5: Run full test suite**

```bash
npm test
```

Expected: All tests pass (92 tests)

- [ ] **Step 6: Type check**

```bash
npm run type-check
```

Expected: No TypeScript errors

- [ ] **Step 7: Commit**

```bash
git add apps/ai-worker-web/src/shared/api/
git commit -m "feat(enrollment): use sessionId as idempotency key for commitEnrollment

- Pass sessionId as idempotencyKey to apiCall
- Enables safe retries with stable idempotency key
- Activates BFF 5-step orchestration idempotency design

Part of P4 I1"
```

---

## Task 2: Verify Retry Behavior (Manual Test)

**Files:**
- Manual: Browser testing with network throttling

- [ ] **Step 1: Start local stack**

```bash
# Terminal 1: Start ai-worker (admin BFF)
cd apps/ai-worker
uv run ai-worker-api

# Terminal 2: Start Java backend
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw spring-boot:run -pl meeting-api-start -am

# Terminal 3: Start frontend
cd apps/ai-worker-web
npm run dev
```

- [ ] **Step 2: Open browser with DevTools**

Navigate to: `http://localhost:5174/workstation/enrollment`

Open DevTools → Network tab → Enable throttling (Fast 3G)

- [ ] **Step 3: Test retry with same idempotency key**

1. Login and start enrollment flow
2. Select a person, create session
3. Upload audio file, preview
4. Open Network tab, filter for "commit"
5. Click "提交注册" button
6. While request is in flight, disable network briefly
7. Re-enable network and click "提交注册" again

**Expected:**
- First request has `Idempotency-Key: session_xxx` in headers
- Retry request has **same** `Idempotency-Key: session_xxx`
- Backend recognizes duplicate and returns cached response
- No duplicate enrollment created

- [ ] **Step 4: Verify backend idempotency log**

Check ai-worker logs for idempotency handling:

```bash
# Should see:
# [INFO] Commit idempotent: sessionId=session_xxx, cached response returned
```

- [ ] **Step 5: Document verification**

Create: `docs/superpowers/2026-06-15-p4-i1-verification.md`

```markdown
# P4 I1 Verification Results

**Date:** 2026-06-15
**Tested By:** [Your name]

## Test Results

### Unit Tests
- ✅ Test added for idempotency key
- ✅ All 92+ tests passing

### Manual Retry Test
- ✅ First request uses sessionId as idempotency key
- ✅ Retry uses same idempotency key
- ✅ Backend recognizes duplicate
- ✅ No duplicate enrollment created

### Backend Logs
- ✅ Idempotency cache hit confirmed

## Conclusion

P4 I1 complete. commitEnrollment now uses sessionId as stable idempotency key, enabling safe retries and activating BFF's 5-step orchestration idempotency design.
```

- [ ] **Step 6: Commit verification doc**

```bash
git add docs/superpowers/2026-06-15-p4-i1-verification.md
git commit -m "docs: add P4 I1 verification results

Manual retry test confirms:
- sessionId used as idempotency key
- Retries properly deduplicated by backend
- BFF idempotency design activated"
```

---

## Task 3: Update Documentation

**Files:**
- Modify: `todo.md`

- [ ] **Step 1: Mark P4 I1 complete in todo.md**

Location: `todo.md` line 512

Change:
```markdown
- [ ] I1：commitEnrollment 幂等键 = sessionId（激活 BFF 既有的五步编排幂等设计）。
```

To:
```markdown
- [x] I1：commitEnrollment 幂等键 = sessionId（激活 BFF 既有的五步编排幂等设计）。（完成于 2026-06-15）
```

- [ ] **Step 2: Commit documentation update**

```bash
git add todo.md
git commit -m "docs: mark P4 I1 complete

commitEnrollment now uses sessionId as stable idempotency key:
- Safe retries with same key
- Backend properly deduplicates
- BFF 5-step orchestration idempotency active

Next: P4 I2 (meeting flow resumption)"
```

---

## Task 4: Push All Changes

**Files:**
- N/A (git operations)

- [ ] **Step 1: Review all commits**

```bash
git log --oneline master..HEAD
```

Expected: 3 commits for P4 I1

- [ ] **Step 2: Push to remote**

```bash
git push origin master
```

Expected: Push succeeds

- [ ] **Step 3: Verify remote**

```bash
git log origin/master --oneline -3
```

Expected: Your 3 commits appear

---

## Self-Review Checklist

### Spec Coverage
✅ **Requirement:** Use sessionId as idempotency key → Task 1  
✅ **Requirement:** Enable safe retries → Task 2 manual test  
✅ **Requirement:** Activate BFF idempotency design → Verified in Task 2  

### No Placeholders
✅ All code blocks complete  
✅ All file paths absolute  
✅ All commands have expected output  
✅ No TBD/TODO markers  

### Type Consistency
✅ `sessionId: string` used consistently  
✅ `idempotencyKey: string` matches apiCall signature  

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-15-p4-i1-idempotency-key.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
