# P3 Meeting-Web Code Review Remediation - Index

> **Overall Status:** Code review remediation for `apps/meeting-web`  
> **Date:** 2026-06-13  
> **Source:** Code review report findings (14 Critical + 42 Important issues)  
> **Branch:** `fix/review-remediation-p3-meeting-web` (to be created)

## Overview

This index tracks the 7 task groups for P3 (meeting-web frontend fixes). Each group has its own detailed implementation plan.

## Task Groups

### Critical (C) - Must Fix

| Group | Title | Plan Document | Dependencies | Status |
|-------|-------|---------------|--------------|--------|
| C1 | Speaker enrollment flow refactor | `2026-06-13-p3-c1-speaker-enrollment.md` | None | ⏳ Pending |
| C2 | SSE reducer fix | `2026-06-13-p3-c2-sse-reducer.md` | None | ⏳ Pending |
| C3 | 401 refresh with CSRF | `2026-06-13-p3-c3-auth-refresh.md` | **P2.8 complete** ✅ | ⏳ Pending |

### Important (I) - Should Fix

| Group | Title | Plan Document | Dependencies | Status |
|-------|-------|---------------|--------------|--------|
| I1 | Mutation wrapper & idempotency | `2026-06-13-p3-i1-mutations.md` | None | ⏳ Pending |
| I2 | Cache invalidation matrix | `2026-06-13-p3-i2-cache-invalidation.md` | None | ⏳ Pending |
| I3 | SSE hardening | `2026-06-13-p3-i3-sse-hardening.md` | C2 | ⏳ Pending |
| I4 | Upload flow fixes | `2026-06-13-p3-i4-upload-fixes.md` | None | ⏳ Pending |
| I5 | UI improvements | `2026-06-13-p3-i5-ui-improvements.md` | None | ⏳ Pending |

## Execution Order

**Recommended sequence:**

1. **Phase 1 (parallel):** C1, C2, I1, I2, I4, I5 - all independent
2. **Phase 2:** C3 - requires P2.8 backend (✅ complete)
3. **Phase 3:** I3 - builds on C2

**Alternative (priority-first):**

1. C3 (unblocks P3 auth flow)
2. C2 (fixes task status handling)
3. C1 (fixes speaker enrollment)
4. I1-I5 (quality improvements)

## Scope Per Group

### C1: Speaker Enrollment Refactor
**Problem:** Speaker enrollment creates temporary "carrier meetings" that pollute the meeting list.  
**Solution:** Use generic `/api/files` + direct enrollment API.  
**Files:** ~5 files (SpeakerEnrollPanel, queries, API client)

### C2: SSE Reducer Fix
**Problem:** `TASK_STEP_UPDATED` overwrites task-level status; `TASK_COMPLETED` ignores `PARTIAL_SUCCEEDED`.  
**Solution:** Update reducer to preserve task status, read event.status field.  
**Files:** 1 file (sse-reducer.ts) + tests

### C3: 401 Refresh Flow
**Problem:** No token refresh on 401; no logout button in Shell.  
**Solution:** Single-flight refresh with X-CSRF-Token, clear token on failure, add Shell logout.  
**Files:** ~4 files (client.ts, auth.ts, Shell.tsx, interceptor)

### I1: Mutation Wrapper
**Problem:** Idempotency keys not generated or reused on retry; no unified error handling.  
**Solution:** Wrap mutations to generate/reuse keys, centralize error handling.  
**Files:** ~3 files (mutation-wrapper.ts, queries updates)

### I2: Cache Invalidation
**Problem:** Scattered invalidation logic; missing invalidations after edits.  
**Solution:** Centralized `invalidateAfter(event, qc)` matrix.  
**Files:** 2 files (invalidation-matrix.ts, event handlers)

### I3: SSE Hardening
**Problem:** lastEventId not tracked; no exponential backoff; ExportsPage uses raw EventSource.  
**Solution:** Add lastEventId getter, exponential backoff, fetch-SSE for exports.  
**Files:** ~3 files (sse hooks, ExportsPage)

### I4: Upload Flow Fixes
**Problem:** AbortController not passed to workers; terminal state can be revived; missing error handling.  
**Solution:** Thread abort signal, guard terminal transitions, add try/catch.  
**Files:** ~2 files (upload reducer, AudioUploadPage)

### I5: UI Improvements
**Problem:** Transcript not virtualized; no confirmation for destructive actions; hardcoded credentials.  
**Solution:** Add react-virtual, confirmation dialogs, remove hardcoded creds.  
**Files:** ~4 files (TranscriptPage, SpeakerProfilesPage, LoginPage)

## Testing Strategy

Each task group includes:
- Unit tests for logic changes (vitest)
- Component tests for UI changes (React Testing Library)
- Integration smoke tests where applicable

**No E2E changes planned** - Playwright tests remain in current scope.

## Acceptance Criteria (All Tasks)

- ✅ All TypeScript compiles (`npx tsc --noEmit`)
- ✅ All tests pass (`npm test`)
- ✅ ESLint clean (`npm run lint`)
- ✅ No console errors in dev mode
- ✅ Each task committed with descriptive message
- ✅ Todo.md updated to mark P3 complete

## Notes

- **Branch strategy:** Create `fix/review-remediation-p3-meeting-web` from current P2 branch
- **Context window:** Each task group is self-contained to fit in agent context
- **Parallel execution:** Groups C1, C2, I1, I2, I4, I5 can run in parallel
- **Sequential dependency:** I3 depends on C2 completing first

## Next Steps

1. Review this index with user
2. Create detailed plans for each group (start with C3 - highest priority)
3. Execute using subagent-driven-development
4. Update status table as groups complete
