# P3 C2: SSE Reducer Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix SSE reducer bugs where TASK_STEP_UPDATED overwrites task-level status and TASK_COMPLETED ignores PARTIAL_SUCCEEDED status.

**Architecture:** Update sse-reducer.ts to preserve task-level status when processing step updates, and read event.status for TASK_COMPLETED events to support all terminal statuses.

**Tech Stack:** TypeScript strict, vitest for testing

**Dependencies:** None (independent task)

---

## Problem Analysis

### Bug 1: TASK_STEP_UPDATED Overwrites Task Status
**Current behavior (line 56 in sse-reducer.ts):**
```typescript
case "TASK_STEP_UPDATED":
  return {
    ...state,
    status: event.status,  // ❌ BUG: Overwrites task-level status with step status
    ...
  };
```

**Issue:** When a step updates (e.g., ASR step goes RUNNING → SUCCEEDED), the task-level status should NOT change to SUCCEEDED. Task status transitions are independent from step status.

**Fix:** Remove `status: event.status` from TASK_STEP_UPDATED case. Only update the step in the steps array.

### Bug 2: TASK_COMPLETED Ignores event.status
**Current behavior (line 89 in sse-reducer.ts):**
```typescript
case "TASK_COMPLETED":
  return {
    ...state,
    status: "SUCCEEDED",  // ❌ BUG: Hardcoded, ignores PARTIAL_SUCCEEDED
    phase: "TERMINAL" as ProcessingTaskPhase,
  };
```

**Issue:** Backend can send `TASK_COMPLETED` with `status=PARTIAL_SUCCEEDED` (some steps failed but task didn't fully fail). Frontend hardcodes to SUCCEEDED and loses this information.

**Fix:** Read `event.status` instead of hardcoding: `status: event.status || "SUCCEEDED"`

---

## File Structure

**Files to Modify:**
- `src/shared/utils/sse-reducer.ts:54-64,88-94` - Fix both bugs
- `src/shared/utils/__tests__/sse-reducer.test.ts` - Add regression tests

**Files to Create:**
- None (all modifications)

---

## Task 1: Fix TASK_STEP_UPDATED Bug

**Files:**
- Modify: `src/shared/utils/sse-reducer.ts:54-64`

- [ ] **Step 1: Write failing test first**

Add to `src/shared/utils/__tests__/sse-reducer.test.ts`:

```typescript
describe('sseReducer - bug fixes', () => {
  it('TASK_STEP_UPDATED preserves task-level status', () => {
    const initial: TaskSnapshot = {
      ...createInitialSnapshot(),
      taskId: 'task_1',
      meetingId: 'meeting_1',
      status: 'RUNNING',
      phase: 'WORKER_DAG_RUNNING',
      steps: [
        { stepName: 'ASR', status: 'RUNNING', progress: 50, source: 'AI_WORKER_CALLBACK' },
        { stepName: 'DIARIZATION', status: 'PENDING', progress: 0, source: 'AI_WORKER_CALLBACK' },
      ],
    };

    const event: TaskEvent = {
      eventType: 'TASK_STEP_UPDATED',
      taskId: 'task_1',
      stepName: 'ASR',
      status: 'SUCCEEDED',
      progress: 100,
      completedSteps: ['ASR'],
    };

    const result = sseReducer(initial, event);

    // Task status should remain RUNNING (not become SUCCEEDED)
    expect(result.status).toBe('RUNNING');
    // Step status should update
    expect(result.steps[0].status).toBe('SUCCEEDED');
    expect(result.steps[0].progress).toBe(100);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd apps/meeting-web
npm test src/shared/utils/__tests__/sse-reducer.test.ts
```

Expected: FAIL - "Expected 'RUNNING', received 'SUCCEEDED'"

- [ ] **Step 3: Fix the bug**

Update `src/shared/utils/sse-reducer.ts` line 54-64:

```typescript
case "TASK_STEP_UPDATED":
  return {
    ...state,
    // Remove: status: event.status,  // ❌ Bug removed
    currentStep: event.stepName ?? state.currentStep,
    steps: state.steps.map((s) =>
      s.stepName === event.stepName
        ? { ...s, status: event.status as TaskStep["status"], progress: event.progress ?? s.progress }
        : s
    ),
    completedSteps: event.completedSteps ?? state.completedSteps,
  };
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test src/shared/utils/__tests__/sse-reducer.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/shared/utils/sse-reducer.ts src/shared/utils/__tests__/sse-reducer.test.ts
git commit -m "fix(sse): TASK_STEP_UPDATED preserves task-level status"
```

---

## Task 2: Fix TASK_COMPLETED Bug

**Files:**
- Modify: `src/shared/utils/sse-reducer.ts:88-94`

- [ ] **Step 1: Write failing test first**

Add to `src/shared/utils/__tests__/sse-reducer.test.ts`:

```typescript
it('TASK_COMPLETED reads event.status for PARTIAL_SUCCEEDED', () => {
  const initial: TaskSnapshot = {
    ...createInitialSnapshot(),
    taskId: 'task_1',
    meetingId: 'meeting_1',
    status: 'RUNNING',
    phase: 'WORKER_DAG_RUNNING',
  };

  const event: TaskEvent = {
    eventType: 'TASK_COMPLETED',
    taskId: 'task_1',
    status: 'PARTIAL_SUCCEEDED',  // Some steps failed
  };

  const result = sseReducer(initial, event);

  expect(result.status).toBe('PARTIAL_SUCCEEDED');
  expect(result.phase).toBe('TERMINAL');
});

it('TASK_COMPLETED defaults to SUCCEEDED if status missing', () => {
  const initial: TaskSnapshot = {
    ...createInitialSnapshot(),
    taskId: 'task_1',
    status: 'RUNNING',
  };

  const event: TaskEvent = {
    eventType: 'TASK_COMPLETED',
    taskId: 'task_1',
    // No status field
  };

  const result = sseReducer(initial, event);

  expect(result.status).toBe('SUCCEEDED');
  expect(result.phase).toBe('TERMINAL');
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npm test src/shared/utils/__tests__/sse-reducer.test.ts
```

Expected: FAIL - "Expected 'PARTIAL_SUCCEEDED', received 'SUCCEEDED'"

- [ ] **Step 3: Fix the bug**

Update `src/shared/utils/sse-reducer.ts` line 88-94:

```typescript
case "TASK_COMPLETED":
  return {
    ...state,
    status: event.status || "SUCCEEDED",  // ✅ Read from event, fallback to SUCCEEDED
    phase: "TERMINAL" as ProcessingTaskPhase,
  };
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test src/shared/utils/__tests__/sse-reducer.test.ts
```

Expected: PASS (3 new tests)

- [ ] **Step 5: Commit**

```bash
git add src/shared/utils/sse-reducer.ts src/shared/utils/__tests__/sse-reducer.test.ts
git commit -m "fix(sse): TASK_COMPLETED reads event.status for PARTIAL_SUCCEEDED"
```

---

## Task 3: Run Full Test Suite

**Files:**
- None (verification task)

- [ ] **Step 1: Run all tests**

```bash
npm test
```

Expected: All tests pass (no regressions)

- [ ] **Step 2: Run TypeScript check**

```bash
npx tsc --noEmit
```

Expected: No errors

- [ ] **Step 3: Verify sse-reducer.test.ts coverage**

```bash
npm test src/shared/utils/__tests__/sse-reducer.test.ts -- --coverage
```

Expected: High coverage on sse-reducer.ts

---

## Task 4: Update Todo

**Files:**
- Modify: `todo.md:501`

- [ ] **Step 1: Mark C2 complete**

Change line 501 from:
```markdown
- [ ] C2：sse-reducer——`TASK_STEP_UPDATED` 只更新 step 不碰任务级 status；`TASK_COMPLETED` 取 `event.status`（支持 `PARTIAL_SUCCEEDED`）。
```

To:
```markdown
- [x] C2：sse-reducer——`TASK_STEP_UPDATED` 只更新 step 不碰任务级 status；`TASK_COMPLETED` 取 `event.status`（支持 `PARTIAL_SUCCEEDED`）。
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P3 C2 complete"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-p3-c2-sse-reducer.md`.

**Execution:** Use superpowers:subagent-driven-development
