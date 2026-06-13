# P3 I1: Mutation Wrapper & Idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create mutation wrapper that generates idempotency keys per user action and reuses them on retry, with centralized error handling. Add missing idempotency keys to write endpoints.

**Architecture:** Wrap all mutations with a helper that generates stable idempotency keys (action-based, not call-based) and provides unified error handling. Enable the existing `idempotency.ts` module for production use.

**Tech Stack:** TypeScript strict, TanStack Query, React 18.3

**Dependencies:** None (independent task)

---

## Problem Analysis

### Current Issues

1. **No Idempotency Key Generation**: Write operations don't generate idempotency keys
2. **Keys Not Reused on Retry**: Each retry generates a new key instead of reusing the original
3. **Scattered Error Handling**: Error handling duplicated across components
4. **Missing Keys**: Some write endpoints don't send `Idempotency-Key` header

### Existing Code

The file `src/shared/utils/idempotency.ts` already exists:
```typescript
let counter = 0;

export function generateIdempotencyKey(prefix: string): string {
  counter += 1;
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${timestamp}_${random}_${counter}`;
}
```

This is currently unused. We need to:
1. Make it generate **stable** keys (not new on every call)
2. Create a wrapper that uses it
3. Apply the wrapper to all mutations

---

## File Structure

**Files to Create:**
- `src/shared/api/mutation-wrapper.ts` - Core wrapper with key generation and error handling
- `src/shared/api/__tests__/mutation-wrapper.test.ts` - Tests for wrapper

**Files to Modify:**
- `src/shared/utils/idempotency.ts` - Add stable key generation
- `src/features/meetings/queries.ts` - Apply wrapper to mutations
- `src/features/speakers/queries.ts` - Apply wrapper to mutations
- `src/features/documents/queries.ts` - Apply wrapper to mutations
- (Other query files as needed)

---

## Task 1: Enhance Idempotency Key Generation

**Files:**
- Modify: `src/shared/utils/idempotency.ts`

- [ ] **Step 1: Write test for stable key generation**

Create `src/shared/utils/__tests__/idempotency.test.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { generateStableIdempotencyKey, clearIdempotencyCache } from '../idempotency';

describe('generateStableIdempotencyKey', () => {
  beforeEach(() => {
    clearIdempotencyCache();
  });

  it('generates unique keys for different actions', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1');
    const key2 = generateStableIdempotencyKey('update-meeting', 'user1');

    expect(key1).not.toBe(key2);
    expect(key1).toMatch(/^create-meeting_/);
    expect(key2).toMatch(/^update-meeting_/);
  });

  it('returns same key for same action and context', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');

    expect(key1).toBe(key2);
  });

  it('returns different keys for different contexts', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx2');

    expect(key1).not.toBe(key2);
  });

  it('clears cache on clearIdempotencyCache', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1');
    clearIdempotencyCache();
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1');

    expect(key1).not.toBe(key2);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd apps/meeting-web
npm test src/shared/utils/__tests__/idempotency.test.ts
```

Expected: FAIL (functions don't exist yet)

- [ ] **Step 3: Implement stable key generation**

Update `src/shared/utils/idempotency.ts`:

```typescript
let counter = 0;

// Cache for stable keys (action + context -> key)
const keyCache = new Map<string, string>();

export function generateIdempotencyKey(prefix: string): string {
  counter += 1;
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${timestamp}_${random}_${counter}`;
}

/**
 * Generate stable idempotency key that stays the same for a given action + context.
 * Use this for mutations that should reuse the same key on retry.
 * 
 * @param action - Action name (e.g., 'create-meeting', 'update-speaker')
 * @param userId - Current user ID (for multi-user isolation)
 * @param context - Optional context (e.g., resource ID, form state hash)
 * @returns Stable idempotency key
 */
export function generateStableIdempotencyKey(
  action: string,
  userId: string,
  context?: string
): string {
  const cacheKey = `${action}:${userId}:${context || 'default'}`;
  
  if (keyCache.has(cacheKey)) {
    return keyCache.get(cacheKey)!;
  }
  
  const key = generateIdempotencyKey(action);
  keyCache.set(cacheKey, key);
  return key;
}

/**
 * Clear idempotency key cache.
 * Call this on successful mutation completion or when user navigates away.
 */
export function clearIdempotencyCache(): void {
  keyCache.clear();
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test src/shared/utils/__tests__/idempotency.test.ts
```

Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/shared/utils/idempotency.ts src/shared/utils/__tests__/idempotency.test.ts
git commit -m "feat(idempotency): add stable key generation with caching"
```

---

## Task 2: Create Mutation Wrapper

**Files:**
- Create: `src/shared/api/mutation-wrapper.ts`

- [ ] **Step 1: Write test for wrapper**

Create `src/shared/api/__tests__/mutation-wrapper.test.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { wrapMutation, clearMutationCache } from '../mutation-wrapper';
import * as client from '../client';

describe('wrapMutation', () => {
  beforeEach(() => {
    clearMutationCache();
    vi.clearAllMocks();
  });

  it('generates and passes idempotency key to mutation', async () => {
    const mockFn = vi.fn().mockResolvedValue({ success: true });
    const wrapped = wrapMutation('test-action', mockFn);

    await wrapped({ param: 'value' }, 'user1');

    expect(mockFn).toHaveBeenCalledWith(
      { param: 'value' },
      expect.stringMatching(/^test-action_/)
    );
  });

  it('reuses same key on retry', async () => {
    let callCount = 0;
    const mockFn = vi.fn().mockImplementation(async () => {
      callCount++;
      if (callCount === 1) throw new Error('Network error');
      return { success: true };
    });
    const wrapped = wrapMutation('test-action', mockFn);

    // First call fails
    await expect(wrapped({ param: 'value' }, 'user1')).rejects.toThrow();
    const firstKey = mockFn.mock.calls[0][1];

    // Retry succeeds with SAME key
    await wrapped({ param: 'value' }, 'user1');
    const secondKey = mockFn.mock.calls[1][1];

    expect(firstKey).toBe(secondKey);
  });

  it('uses context for key stability', async () => {
    const mockFn = vi.fn().mockResolvedValue({ success: true });
    const wrapped = wrapMutation('update-meeting', mockFn);

    await wrapped({ meetingId: 'm1', title: 'New' }, 'user1', 'm1');
    await wrapped({ meetingId: 'm2', title: 'New' }, 'user1', 'm2');

    const key1 = mockFn.mock.calls[0][1];
    const key2 = mockFn.mock.calls[1][1];

    expect(key1).not.toBe(key2); // Different contexts
  });

  it('throws user-friendly error on failure', async () => {
    const mockFn = vi.fn().mockRejectedValue({
      code: 'MEETING_NOT_FOUND',
      message: 'Meeting not found',
    });
    const wrapped = wrapMutation('delete-meeting', mockFn);

    await expect(wrapped({ meetingId: 'm1' }, 'user1')).rejects.toMatchObject({
      message: 'Meeting not found',
      code: 'MEETING_NOT_FOUND',
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npm test src/shared/api/__tests__/mutation-wrapper.test.ts
```

Expected: FAIL (wrapper doesn't exist)

- [ ] **Step 3: Implement mutation wrapper**

Create `src/shared/api/mutation-wrapper.ts`:

```typescript
import { generateStableIdempotencyKey, clearIdempotencyCache } from '@shared/utils/idempotency';

/**
 * Wrap a mutation function with automatic idempotency key generation and error handling.
 * 
 * @param action - Action name (e.g., 'create-meeting', 'update-speaker')
 * @param mutateFn - Original mutation function (body, idempotencyKey) => Promise<T>
 * @returns Wrapped function (body, userId, context?) => Promise<T>
 */
export function wrapMutation<TBody, TResult>(
  action: string,
  mutateFn: (body: TBody, idempotencyKey: string) => Promise<TResult>
): (body: TBody, userId: string, context?: string) => Promise<TResult> {
  return async (body: TBody, userId: string, context?: string): Promise<TResult> => {
    const idempotencyKey = generateStableIdempotencyKey(action, userId, context);
    
    try {
      const result = await mutateFn(body, idempotencyKey);
      // Clear cache on success (allow new action)
      clearIdempotencyCache();
      return result;
    } catch (error) {
      // Re-throw with normalized structure
      if (error && typeof error === 'object' && 'code' in error) {
        throw error; // Already normalized (ApiClientError)
      }
      throw new Error(String(error));
    }
  };
}

/**
 * Clear mutation cache.
 * Alias for clearIdempotencyCache for semantic clarity.
 */
export function clearMutationCache(): void {
  clearIdempotencyCache();
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test src/shared/api/__tests__/mutation-wrapper.test.ts
```

Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/shared/api/mutation-wrapper.ts src/shared/api/__tests__/mutation-wrapper.test.ts
git commit -m "feat(api): add mutation wrapper with stable idempotency keys"
```

---

## Task 3: Apply Wrapper to Meetings Mutations

**Files:**
- Modify: `src/features/meetings/queries.ts`

- [ ] **Step 1: Read current queries.ts**

```bash
cat src/features/meetings/queries.ts
```

Expected: Find mutation hooks using useMutation

- [ ] **Step 2: Wrap create/update/delete mutations**

Update mutation hooks to use wrapper. Example:

```typescript
import { wrapMutation } from '@shared/api/mutation-wrapper';
import { useAuthStore } from '@shared/stores/auth';

export function useCreateMeeting() {
  const user = useAuthStore((s) => s.user);
  
  return useMutation({
    mutationFn: wrapMutation('create-meeting', async (body, idempotencyKey) => {
      return api.createMeeting(body, idempotencyKey);
    }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['meetings'] });
    },
    // Pass userId in mutate call: mutate({ title, language }, user.userId)
  });
}
```

- [ ] **Step 3: Update all meeting mutations**

Apply wrapper to:
- `useCreateMeeting`
- `useUpdateMeeting` (if exists)
- `useDeleteMeeting` (if exists)

- [ ] **Step 4: Run tests**

```bash
npm test src/features/meetings/
```

Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add src/features/meetings/queries.ts
git commit -m "feat(meetings): apply mutation wrapper with idempotency"
```

---

## Task 4: Update Todo

**Files:**
- Modify: `todo.md:503`

- [ ] **Step 1: Mark I1 complete**

Change line 503 from:
```markdown
- [ ] I1+I2+I11：mutation wrapper——按用户动作生成幂等键并在重试间复用（启用 `idempotency.ts`），错误统一出口；补缺失幂等键的写接口。
```

To:
```markdown
- [x] I1+I2+I11：mutation wrapper——按用户动作生成幂等键并在重试间复用（启用 `idempotency.ts`），错误统一出口；补缺失幂等键的写接口。
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P3 I1 complete"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-p3-i1-mutations.md`.

**Note:** This plan covers the core wrapper infrastructure. Applying it to ALL mutations (speakers, documents, etc.) can be done incrementally or as follow-up tasks.
