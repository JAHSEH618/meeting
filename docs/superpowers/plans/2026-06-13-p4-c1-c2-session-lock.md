# P4 C1+C2: Session Lock & BFF Validation Implementation Plan

> **For agentic workers:** Execute all tasks as one batch for efficiency.

**Goal:** Lock person selection after session creation + add personId validation in BFF commit to prevent wrong binding (409 ENROLLMENT_PERSON_MISMATCH). Add "restart" path and handle ENROLLMENT_SESSION_NOT_FOUND.

**Context:** `apps/ai-worker-web` - Operator "workstation" SPA for speaker enrollment

---

## Issues

1. **No lock after session creation**: Users can change person selection after session created, causing binding errors
2. **No personId validation**: BFF doesn't validate personId matches session, causing 409 errors
3. **No restart flow**: Users stuck on errors, no way to start over
4. **Generic error handling**: ENROLLMENT_SESSION_NOT_FOUND needs specific UI feedback

---

## Tasks

### Task 1: Lock Person Selection After Session Creation
- **Files**: Find enrollment flow components (likely `src/features/enrollment/*.tsx`)
- **Fix**: After session creation API returns sessionId, disable person selector
- **UI**: Show message "会话已创建，人员已锁定" + "重新开始" button
- **Commit**: "feat(enrollment): lock person selection after session creation"

### Task 2: Add personId Validation to BFF Commit
- **File**: `apps/ai-worker/ai_worker/admin/enrollment.py` (BFF)
- **Fix**: Before commit, validate `session.person_id == request.person_id`, return 409 ENROLLMENT_PERSON_MISMATCH if mismatch
- **Commit**: "feat(bff): add personId validation to prevent enrollment mismatch"

### Task 3: Add Restart Flow
- **Files**: Enrollment components
- **Fix**: Add "重新开始" button that clears state (sessionId, personId, files) and returns to initial step
- **Commit**: "feat(enrollment): add restart button to reset enrollment flow"

### Task 4: Handle ENROLLMENT_SESSION_NOT_FOUND
- **Files**: Error handling components
- **Fix**: Detect error code, show specific message: "声纹会话已失效，请重新开始"
- **Commit**: "feat(enrollment): add specific error handling for session not found"

### Task 5: Verify & Mark Complete
- Run tests, TypeScript check
- Update `todo.md:511` from `- [ ]` to `- [x]`
- **Commit**: "docs: mark P4 C1+C2 complete"

---

## Execution

Execute all 5 tasks as batch. Report DONE with summary.
