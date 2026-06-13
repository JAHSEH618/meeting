# P3 I3: SSE Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden SSE (Server-Sent Events) implementation with proper lastEventId handling, exponential backoff, and fetch-SSE for exports.

**Architecture:** 
1. Use getter for lastEventId (don't store in ref directly)
2. Add exponential backoff on reconnection failures
3. Replace raw EventSource with fetch-based SSE in exports page

**Tech Stack:** TypeScript strict, React 18.3, fetch API

**Dependencies:** P3 C2 (SSE reducer fixes) ✅ Complete

---

## Problem Analysis

### Current Issues

1. **lastEventId Not Tracked**: Missing resume-from-last-event on reconnection
2. **No Backoff**: Immediate reconnection on failure creates request storms
3. **Export SSE Uses Raw EventSource**: Doesn't support custom headers (e.g., auth)

### Files to Modify

- `src/shared/hooks/use-sse.ts` - Add lastEventId getter, exponential backoff
- `src/features/exports/ExportPage.tsx` (or similar) - Replace EventSource with fetch-SSE

---

## Task 1: Add lastEventId Getter to SSE Hook

**Files:**
- Modify: `src/shared/hooks/use-sse.ts`

- [ ] **Step 1: Read current use-sse.ts implementation**

```bash
cd apps/meeting-web
cat src/shared/hooks/use-sse.ts
```

Expected: Find current SSE connection logic

- [ ] **Step 2: Add lastEventId tracking**

Store lastEventId from events:

```typescript
const lastEventIdRef = useRef<string | null>(null);

// In event handler
eventSource.addEventListener('message', (event) => {
  if (event.lastEventId) {
    lastEventIdRef.current = event.lastEventId;
  }
  // ... process event
});
```

- [ ] **Step 3: Use lastEventId on reconnection**

When reconnecting, append `?lastEventId=...` to URL:

```typescript
const connectUrl = lastEventIdRef.current
  ? `${url}?lastEventId=${lastEventIdRef.current}`
  : url;

const eventSource = new EventSource(connectUrl);
```

- [ ] **Step 4: Add getter for lastEventId**

Return lastEventId in hook return value:

```typescript
return {
  // ... existing returns
  getLastEventId: () => lastEventIdRef.current,
};
```

- [ ] **Step 5: Write tests**

Create or update `src/shared/hooks/__tests__/use-sse.test.ts`:

```typescript
it('tracks and uses lastEventId on reconnection', () => {
  // Test that lastEventId is appended to URL on reconnect
});
```

- [ ] **Step 6: Commit**

```bash
git add src/shared/hooks/use-sse.ts src/shared/hooks/__tests__/use-sse.test.ts
git commit -m "feat(sse): add lastEventId tracking with getter"
```

---

## Task 2: Add Exponential Backoff

**Files:**
- Modify: `src/shared/hooks/use-sse.ts`

- [ ] **Step 1: Add backoff state**

```typescript
const reconnectDelayRef = useRef(1000); // Start at 1s
const MAX_RECONNECT_DELAY = 30000; // Cap at 30s
```

- [ ] **Step 2: Implement backoff logic**

On reconnection:

```typescript
const reconnect = () => {
  setTimeout(() => {
    connect();
    // Double delay for next time, up to max
    reconnectDelayRef.current = Math.min(
      reconnectDelayRef.current * 2,
      MAX_RECONNECT_DELAY
    );
  }, reconnectDelayRef.current);
};
```

- [ ] **Step 3: Reset delay on successful connection**

When connection succeeds:

```typescript
eventSource.addEventListener('open', () => {
  reconnectDelayRef.current = 1000; // Reset to 1s
});
```

- [ ] **Step 4: Write tests**

```typescript
it('uses exponential backoff on reconnection failures', () => {
  // Test that delay doubles: 1s, 2s, 4s, 8s, 16s, 30s (capped)
});
```

- [ ] **Step 5: Commit**

```bash
git add src/shared/hooks/use-sse.ts src/shared/hooks/__tests__/use-sse.test.ts
git commit -m "feat(sse): add exponential backoff on reconnection"
```

---

## Task 3: Replace EventSource with Fetch-SSE in Exports

**Files:**
- Find and modify: Export page component

- [ ] **Step 1: Find export page with raw EventSource**

```bash
grep -r "new EventSource" src/features/exports/ --include="*.tsx" --include="*.ts"
```

Expected: Find file using raw EventSource for export SSE

- [ ] **Step 2: Create fetch-SSE utility**

Create `src/shared/utils/fetch-sse.ts`:

```typescript
export async function* fetchSSE(
  url: string,
  options?: { signal?: AbortSignal; headers?: HeadersInit }
): AsyncGenerator<MessageEvent, void, unknown> {
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: 'text/event-stream',
      ...options?.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`SSE fetch failed: ${response.status}`);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('Response body is not readable');
  }

  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const data = line.slice(6);
          yield new MessageEvent('message', { data });
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
```

- [ ] **Step 3: Update export page to use fetch-SSE**

Replace:

```typescript
const eventSource = new EventSource(`/api/exports/${id}/stream`);
```

With:

```typescript
const abortController = new AbortController();

(async () => {
  try {
    for await (const event of fetchSSE(`/api/exports/${id}/stream`, {
      signal: abortController.signal,
      headers: { Authorization: `Bearer ${token}` },
    })) {
      // Process event
      const data = JSON.parse(event.data);
      // ... handle data
    }
  } catch (error) {
    if (error.name !== 'AbortError') {
      console.error('SSE error:', error);
    }
  }
})();

// Cleanup
return () => abortController.abort();
```

- [ ] **Step 4: Write tests for fetch-SSE**

Create `src/shared/utils/__tests__/fetch-sse.test.ts`:

```typescript
it('parses SSE events from fetch response', async () => {
  // Mock fetch with SSE response
  // Verify events are yielded correctly
});
```

- [ ] **Step 5: Commit**

```bash
git add src/shared/utils/fetch-sse.ts src/shared/utils/__tests__/fetch-sse.test.ts src/features/exports/...
git commit -m "feat(exports): replace EventSource with fetch-SSE for auth support"
```

---

## Task 4: Run Full Test Suite

**Files:**
- None (verification)

- [ ] **Step 1: Run all tests**

```bash
npm test
```

Expected: All tests pass (200+)

- [ ] **Step 2: TypeScript check**

```bash
npx tsc --noEmit
```

Expected: No errors

---

## Task 5: Update Todo

**Files:**
- Modify: `todo.md:505`

- [ ] **Step 1: Mark I3 complete**

Change line 505 from:
```markdown
- [ ] I7+I13：SSE 加固——lastEventId 走 getter、指数退避；导出页弃裸 EventSource 改 fetch-SSE。
```

To:
```markdown
- [x] I7+I13：SSE 加固——lastEventId 走 getter、指数退避；导出页弃裸 EventSource 改 fetch-SSE。
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P3 I3 complete"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-p3-i3-sse-hardening.md`.

**Note:** This plan focuses on core SSE infrastructure hardening. Additional SSE consumers can adopt these patterns incrementally.
