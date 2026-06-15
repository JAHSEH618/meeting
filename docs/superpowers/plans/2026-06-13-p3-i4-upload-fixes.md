# P3 I4: Upload Flow Fixes Implementation Plan

> **For agentic workers:** Execute all tasks as one batch for efficiency.

**Goal:** Fix upload flow issues - AbortController propagation, state management, explicit partSizeBytes, finalize error handling.

**Issues:**
1. **AbortController not passed to worker**: Cancel doesn't stop upload
2. **Part-start events resurrect terminal states**: COMPLETED → UPLOADING
3. **partSizeBytes implicit**: Causes backend validation failures
4. **Finalize query lacks try/catch**: Errors not handled

---

## Tasks

### Task 1: AbortController Propagation
- **File**: `src/features/meetings/use-upload-audio.ts`
- **Fix**: Pass `signal` from AbortController to worker, propagate to fetch calls
- **Commit**: "fix(upload): propagate AbortController to worker"

### Task 2: Terminal State Protection
- **File**: `src/shared/stores/upload-store.ts`
- **Fix**: Add guard in reducer: if state is terminal (COMPLETED/FAILED/CANCELLED), ignore part-start events
- **Commit**: "fix(upload): prevent part-start from resurrecting terminal states"

### Task 3: Explicit partSizeBytes
- **File**: `src/features/meetings/use-upload-audio.ts`
- **Fix**: Calculate and pass explicit `partSizeBytes` in createAudioUpload call
- **Commit**: "fix(upload): pass explicit partSizeBytes to avoid backend validation errors"

### Task 4: Finalize Error Handling
- **File**: `src/features/meetings/use-upload-audio.ts`
- **Fix**: Wrap finalize + post-finalize query in try/catch, set upload state to FAILED on error
- **Commit**: "fix(upload): add try/catch around finalize and post-query"

### Task 5: Verify & Mark Complete
- Run tests: `npm test`
- TypeScript: `npx tsc --noEmit`
- Update `todo.md:506` from `- [ ]` to `- [x]`
- **Commit**: "docs: mark P3 I4 complete"

---

## Execution

Execute all 5 tasks as batch. Report DONE with summary.
