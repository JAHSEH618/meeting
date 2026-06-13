# P3 I5: UI Improvements Implementation Plan

> **For agentic workers:** Execute all tasks as one batch for efficiency.

**Goal:** UI improvements - transcript virtualization, speaker deletion confirmation, remove hardcoded credentials.

**Issues:**
1. **Transcript not virtualized**: Large transcripts cause performance issues
2. **No confirmation for destructive actions**: Speaker deletion has no confirmation dialog
3. **Hardcoded admin/admin123**: Debug credentials still in code

---

## Tasks

### Task 1: Transcript Virtualization
- **File**: `src/features/transcript/TranscriptView.tsx`
- **Install**: `@tanstack/react-virtual`
- **Fix**: Replace flat render with `useVirtualizer` for segment list
- **Commit**: "feat(transcript): add virtualization with @tanstack/react-virtual"

### Task 2: Speaker Deletion Confirmation
- **Files**: Find speaker deletion handlers (e.g., `src/features/speakers/*.tsx`)
- **Fix**: Add `window.confirm()` before deletion API calls: "确定要删除此声纹吗？此操作不可恢复。"
- **Commit**: "feat(speakers): add confirmation dialog for destructive operations"

### Task 3: Remove Hardcoded Credentials
- **Find**: `grep -r "admin/admin123\|admin123" src/ --include="*.tsx" --include="*.ts"`
- **Fix**: Remove hardcoded values, use empty string defaults
- **Commit**: "refactor(auth): remove hardcoded admin credentials from code"

### Task 4: Verify & Mark Complete
- Run tests: `npm test`
- TypeScript: `npx tsc --noEmit`
- Update `todo.md:507` from `- [ ]` to `- [x]`
- **Commit**: "docs: mark P3 I5 complete"

---

## Execution

Execute all 4 tasks as batch. Report DONE with summary.
