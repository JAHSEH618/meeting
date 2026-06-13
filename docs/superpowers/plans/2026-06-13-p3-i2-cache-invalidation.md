# P3 I2: Cache Invalidation Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create centralized cache invalidation matrix that maps events to query invalidation rules, eliminating scattered invalidation logic.

**Architecture:** Single `invalidateAfter(event, queryClient)` function that maps event types to invalidation rules. Consolidate all scattered `queryClient.invalidateQueries()` calls into one matrix.

**Tech Stack:** TypeScript strict, TanStack Query v5, React 18.3

**Dependencies:** None (independent task)

---

## Problem Analysis

### Current Issues

1. **Scattered Invalidation**: Invalidation logic duplicated across components
2. **Missing Invalidations**: Some events don't trigger necessary cache updates
3. **No Matrix**: Hard to see what invalidates what
4. **Race Conditions**: Manual invalidation may miss concurrent updates

### Events That Need Invalidation

From the task description (todo.md line 504):
- 转录编辑 (transcript edit)
- 说话人确认 (speaker confirmation)
- 纪要重生成 (minutes regeneration)
- 建会 (meeting creation)

Additional events discovered from code review:
- Meeting updates (title, participants)
- Speaker enrollment completion
- Document uploads
- Processing task completion
- Export generation

---

## File Structure

**Files to Create:**
- `src/shared/query/invalidation-matrix.ts` - Core invalidation matrix
- `src/shared/query/__tests__/invalidation-matrix.test.ts` - Tests

**Files to Modify:**
- Event handlers that currently call `queryClient.invalidateQueries()` directly
- Remove scattered invalidation, call `invalidateAfter()` instead

---

## Task 1: Create Invalidation Matrix

**Files:**
- Create: `src/shared/query/invalidation-matrix.ts`

- [ ] **Step 1: Write test for matrix**

Create `src/shared/query/__tests__/invalidation-matrix.test.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { invalidateAfter } from '../invalidation-matrix';
import type { QueryClient } from '@tanstack/react-query';

describe('invalidateAfter', () => {
  let mockQueryClient: QueryClient;

  beforeEach(() => {
    mockQueryClient = {
      invalidateQueries: vi.fn(),
    } as unknown as QueryClient;
  });

  it('invalidates meetings list after meeting created', () => {
    invalidateAfter('meeting-created', mockQueryClient, { meetingId: 'm1' });

    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['meetings'],
    });
  });

  it('invalidates meeting detail and list after meeting updated', () => {
    invalidateAfter('meeting-updated', mockQueryClient, { meetingId: 'm1' });

    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledTimes(2);
    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['meetings'],
    });
    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['meeting', 'm1'],
    });
  });

  it('invalidates transcript and minutes after transcript edited', () => {
    invalidateAfter('transcript-edited', mockQueryClient, { meetingId: 'm1' });

    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledTimes(2);
    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['transcript', 'm1'],
    });
    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['minutes', 'm1'],
    });
  });

  it('invalidates speakers after speaker confirmed', () => {
    invalidateAfter('speaker-confirmed', mockQueryClient, { 
      meetingId: 'm1',
      profileId: 'p1'
    });

    expect(mockQueryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['speakers', 'm1'],
    });
  });

  it('does nothing for unknown event types', () => {
    invalidateAfter('unknown-event' as any, mockQueryClient, {});

    expect(mockQueryClient.invalidateQueries).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd apps/meeting-web
npm test src/shared/query/__tests__/invalidation-matrix.test.ts
```

Expected: FAIL (matrix doesn't exist)

- [ ] **Step 3: Implement invalidation matrix**

Create `src/shared/query/invalidation-matrix.ts`:

```typescript
import type { QueryClient } from '@tanstack/react-query';

/**
 * Event types that trigger cache invalidation
 */
export type InvalidationEvent =
  | 'meeting-created'
  | 'meeting-updated'
  | 'meeting-deleted'
  | 'transcript-edited'
  | 'speaker-confirmed'
  | 'speaker-enrolled'
  | 'minutes-regenerated'
  | 'document-uploaded'
  | 'task-completed'
  | 'export-generated';

/**
 * Context passed with invalidation events
 */
export interface InvalidationContext {
  meetingId?: string;
  profileId?: string;
  documentId?: string;
  taskId?: string;
  exportId?: string;
  [key: string]: string | undefined;
}

/**
 * Invalidation rules matrix.
 * Maps event types to query keys that should be invalidated.
 */
const INVALIDATION_MATRIX: Record<
  InvalidationEvent,
  (ctx: InvalidationContext) => Array<{ queryKey: unknown[] }>
> = {
  'meeting-created': () => [
    { queryKey: ['meetings'] },
  ],

  'meeting-updated': (ctx) => [
    { queryKey: ['meetings'] },
    { queryKey: ['meeting', ctx.meetingId] },
  ],

  'meeting-deleted': (ctx) => [
    { queryKey: ['meetings'] },
    { queryKey: ['meeting', ctx.meetingId] },
  ],

  'transcript-edited': (ctx) => [
    { queryKey: ['transcript', ctx.meetingId] },
    { queryKey: ['minutes', ctx.meetingId] },
    { queryKey: ['meeting', ctx.meetingId] }, // Stale status may change
  ],

  'speaker-confirmed': (ctx) => [
    { queryKey: ['speakers', ctx.meetingId] },
    { queryKey: ['transcript', ctx.meetingId] }, // Speaker labels change
  ],

  'speaker-enrolled': (ctx) => [
    { queryKey: ['speaker-profiles'] },
    { queryKey: ['speaker-profile', ctx.profileId] },
  ],

  'minutes-regenerated': (ctx) => [
    { queryKey: ['minutes', ctx.meetingId] },
    { queryKey: ['meeting', ctx.meetingId] }, // Stale status may change
  ],

  'document-uploaded': (ctx) => [
    { queryKey: ['documents', ctx.meetingId] },
    { queryKey: ['meeting', ctx.meetingId] },
  ],

  'task-completed': (ctx) => [
    { queryKey: ['tasks', ctx.meetingId] },
    { queryKey: ['task', ctx.taskId] },
    { queryKey: ['meeting', ctx.meetingId] },
    { queryKey: ['transcript', ctx.meetingId] }, // May be ready now
  ],

  'export-generated': (ctx) => [
    { queryKey: ['exports', ctx.meetingId] },
    { queryKey: ['export', ctx.exportId] },
  ],
};

/**
 * Centralized cache invalidation.
 * Call this after events to ensure consistent cache updates.
 * 
 * @param event - Event type that occurred
 * @param queryClient - TanStack Query client
 * @param context - Event context (IDs, etc.)
 * 
 * @example
 * // After creating a meeting
 * invalidateAfter('meeting-created', queryClient, { meetingId: 'm1' });
 * 
 * // After editing transcript
 * invalidateAfter('transcript-edited', queryClient, { meetingId: 'm1' });
 */
export function invalidateAfter(
  event: InvalidationEvent,
  queryClient: QueryClient,
  context: InvalidationContext
): void {
  const rules = INVALIDATION_MATRIX[event];
  
  if (!rules) {
    console.warn(`[invalidateAfter] Unknown event type: ${event}`);
    return;
  }

  const invalidations = rules(context);
  
  invalidations.forEach((query) => {
    queryClient.invalidateQueries(query);
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test src/shared/query/__tests__/invalidation-matrix.test.ts
```

Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/shared/query/invalidation-matrix.ts src/shared/query/__tests__/invalidation-matrix.test.ts
git commit -m "feat(query): add centralized cache invalidation matrix"
```

---

## Task 2: Apply Matrix to Meetings Queries

**Files:**
- Modify: `src/features/meetings/queries.ts`

- [ ] **Step 1: Replace scattered invalidation with matrix**

Find existing invalidation calls like:
```typescript
onSuccess: () => {
  queryClient.invalidateQueries({ queryKey: ['meetings'] });
}
```

Replace with:
```typescript
import { invalidateAfter } from '@shared/query/invalidation-matrix';

onSuccess: (data) => {
  invalidateAfter('meeting-created', queryClient, { meetingId: data.meetingId });
}
```

- [ ] **Step 2: Apply to all meeting mutations**

Update:
- `useCreateMeeting` → 'meeting-created'
- `useUpdateMeeting` (if exists) → 'meeting-updated'
- `useDeleteMeeting` (if exists) → 'meeting-deleted'

- [ ] **Step 3: Run tests**

```bash
npm test src/features/meetings/
```

Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add src/features/meetings/queries.ts
git commit -m "refactor(meetings): use invalidation matrix"
```

---

## Task 3: Apply Matrix to Transcript Edits

**Files:**
- Find and modify: Components that edit transcripts

- [ ] **Step 1: Find transcript edit handlers**

```bash
grep -r "transcript.*save\|transcript.*edit" src/features/ --include="*.tsx" --include="*.ts"
```

- [ ] **Step 2: Replace invalidation with matrix**

After transcript edit success:
```typescript
invalidateAfter('transcript-edited', queryClient, { meetingId });
```

- [ ] **Step 3: Commit**

```bash
git add <modified files>
git commit -m "refactor(transcript): use invalidation matrix"
```

---

## Task 4: Apply Matrix to Speaker Confirmation

**Files:**
- Find and modify: Speaker confirmation handlers

- [ ] **Step 1: Find speaker confirmation handlers**

```bash
grep -r "confirm.*speaker\|speaker.*confirm" src/features/speakers/ --include="*.tsx" --include="*.ts"
```

- [ ] **Step 2: Replace invalidation with matrix**

After speaker confirmation:
```typescript
invalidateAfter('speaker-confirmed', queryClient, { meetingId, profileId });
```

- [ ] **Step 3: Commit**

```bash
git add <modified files>
git commit -m "refactor(speakers): use invalidation matrix"
```

---

## Task 5: Run Full Test Suite

**Files:**
- None (verification)

- [ ] **Step 1: Run all tests**

```bash
npm test
```

Expected: All tests pass (189+)

- [ ] **Step 2: TypeScript check**

```bash
npx tsc --noEmit
```

Expected: No errors

---

## Task 6: Update Todo

**Files:**
- Modify: `todo.md:504`

- [ ] **Step 1: Mark I2 complete**

Change line 504 from:
```markdown
- [ ] I4+I5+I6+I12：集中失效矩阵 `invalidateAfter(event, qc)`（转录编辑/说话人确认/纪要重生成/建会）。
```

To:
```markdown
- [x] I4+I5+I6+I12：集中失效矩阵 `invalidateAfter(event, qc)`（转录编辑/说话人确认/纪要重生成/建会）。
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P3 I2 complete"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-p3-i2-cache-invalidation.md`.

**Note:** This plan creates the matrix infrastructure and applies it to core features. Additional features can adopt the matrix incrementally.
