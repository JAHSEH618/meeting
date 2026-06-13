# P3 C3: 401 Refresh Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement automatic token refresh on 401 responses using the new `/api/auth/refresh` endpoint with CSRF double-submit pattern, add Shell logout button.

**Architecture:** Intercept 401 responses, attempt single-flight refresh with CSRF token from cookie, retry original request with new access token. On refresh failure, clear tokens and redirect to login. Add logout button to Shell component.

**Tech Stack:** React 18.3, TypeScript strict, Zustand for auth state, TanStack Query for API calls, fetch API with CSRF cookies

**Dependencies:** P2.8 (POST /api/auth/refresh endpoint) ✅ Complete

---

## File Structure

**Files to Modify:**
- `src/shared/api/client.ts` - Add 401 interceptor with single-flight refresh
- `src/services/auth.ts` - Update login to handle refresh token cookie, add refresh logic
- `src/shared/stores/auth.ts` - Ensure token clearing on refresh failure
- `src/components/Shell.tsx` - Add logout button to navigation
- `src/services/__tests__/auth.test.ts` - Add refresh flow tests
- `src/shared/api/__tests__/client.test.ts` - Add 401 intercept tests

**Files to Create:**
- None (all modifications to existing files)

---

## Task 1: Add Refresh Token API Method

**Files:**
- Modify: `src/shared/api/client.ts:146-149`

- [ ] **Step 1: Add refresh() function after logout()**

Add after line 148:

```typescript
export async function refresh() {
  // Read CSRF token from cookie
  const csrfToken = document.cookie
    .split('; ')
    .find(row => row.startsWith('XSRF-TOKEN='))
    ?.split('=')[1];
  
  if (!csrfToken) {
    const error = new Error('CSRF token not found') as ApiClientError;
    error.code = 'CSRF_TOKEN_INVALID';
    error.retryable = false;
    throw error;
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'X-Request-Id': generateId('req'),
    'X-Trace-Id': generateId('trace'),
    'X-CSRF-Token': csrfToken,
  };

  let res: Response;
  try {
    res = await fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      headers,
      credentials: 'include', // Send cookies
    });
  } catch (cause) {
    const error = new Error('网络连接失败') as ApiClientError;
    error.code = 'DEPENDENCY_UNAVAILABLE';
    error.retryable = true;
    error.details = { cause: String(cause) };
    throw error;
  }

  if (!res.ok) {
    const json = (await res.json()) as ApiResponse<unknown>;
    const err = json.error as ApiError;
    const error = new Error(err?.message || 'Refresh failed') as ApiClientError;
    error.code = err?.code || 'REFRESH_TOKEN_INVALID';
    error.retryable = false;
    error.status = res.status;
    throw error;
  }

  const json = (await res.json()) as ApiResponse<{ accessToken: string; expiresAt: string }>;
  return json.data;
}
```

- [ ] **Step 2: Update login() to use credentials: 'include'**

Modify line 139-143 to include credentials:

```typescript
export async function login(username: string, password: string) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'X-Request-Id': generateId('req'),
    'X-Trace-Id': generateId('trace'),
  };

  const res = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ username, password }),
    credentials: 'include', // Receive Set-Cookie
  });

  if (!res.ok) {
    const json = (await res.json()) as ApiResponse<unknown>;
    const err = json.error as ApiError;
    const error = new Error(err?.message || 'Login failed') as ApiClientError;
    error.code = err?.code || 'AUTH_FAILED';
    error.retryable = false;
    error.status = res.status;
    throw error;
  }

  const json = (await res.json()) as ApiResponse<{ 
    accessToken: string; 
    expiresAt: string; 
    user: import("@shared/api/types").AuthUser 
  }>;
  return json.data;
}
```

- [ ] **Step 3: Update logout() to use credentials: 'include'**

Modify line 146-148:

```typescript
export async function logout() {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Request-Id': generateId('req'),
    'X-Trace-Id': generateId('trace'),
  };

  if (authToken) {
    headers['Authorization'] = `Bearer ${authToken}`;
  }

  await fetch(`${API_BASE}/auth/logout`, {
    method: 'POST',
    headers,
    credentials: 'include', // Send refresh token cookie
  });
}
```

- [ ] **Step 4: Commit**

```bash
git add src/shared/api/client.ts
git commit -m "feat(api): add refresh() method with CSRF support"
```

---

## Task 2: Add Single-Flight 401 Interceptor

**Files:**
- Modify: `src/shared/api/client.ts:46-103`

- [ ] **Step 1: Add refresh state tracking at module level**

Add after line 14 (after `let authToken`):

```typescript
let authToken: string | null = null;
let refreshPromise: Promise<{ accessToken: string; expiresAt: string }> | null = null;
```

- [ ] **Step 2: Create handleUnauthorized helper**

Add before the `request()` function (around line 45):

```typescript
async function handleUnauthorized<T>(
  originalMethod: string,
  originalPath: string,
  originalBody?: unknown,
  originalIdempotencyKey?: string,
): Promise<T> {
  // Single-flight refresh: if already refreshing, wait for that
  if (refreshPromise) {
    try {
      const result = await refreshPromise;
      setAuthToken(result.accessToken);
      // Retry original request with new token
      return request<T>(originalMethod, originalPath, originalBody, originalIdempotencyKey);
    } catch {
      // Refresh failed, clear state and throw
      setAuthToken(null);
      refreshPromise = null;
      // Let auth.ts handle redirect to login
      const error = new Error('会话已过期，请重新登录') as ApiClientError;
      error.code = 'AUTH_REQUIRED';
      error.retryable = false;
      error.status = 401;
      throw error;
    }
  }

  // Start new refresh
  refreshPromise = refresh();
  
  try {
    const result = await refreshPromise;
    setAuthToken(result.accessToken);
    refreshPromise = null;
    // Retry original request with new token
    return request<T>(originalMethod, originalPath, originalBody, originalIdempotencyKey);
  } catch (err) {
    refreshPromise = null;
    setAuthToken(null);
    // Re-throw as AUTH_REQUIRED
    const error = new Error('会话已过期，请重新登录') as ApiClientError;
    error.code = 'AUTH_REQUIRED';
    error.retryable = false;
    error.status = 401;
    throw error;
  }
}
```

- [ ] **Step 3: Update request() to intercept 401**

Modify the existing request() function around line 82-88 to add 401 check:

```typescript
  if (res.status === 404) {
    const error = new Error("资源不存在") as ApiClientError;
    error.code = "TASK_NOT_FOUND";
    error.retryable = false;
    error.status = res.status;
    throw error;
  }

  // Intercept 401 for token refresh
  if (res.status === 401 && authToken) {
    return handleUnauthorized<T>(method, path, body, idempotencyKey);
  }

  const json = (await res.json()) as ApiResponse<unknown>;
```

- [ ] **Step 4: Commit**

```bash
git add src/shared/api/client.ts
git commit -m "feat(api): add 401 interceptor with single-flight refresh"
```

---

## Task 3: Update Auth Service for Refresh Flow

**Files:**
- Modify: `src/services/auth.ts:38-51`

- [ ] **Step 1: Add error handling for AUTH_REQUIRED**

Update login callback to catch AUTH_REQUIRED:

```typescript
  const login = useCallback(async (username: string, password: string) => {
    try {
      const result = await api.login(username, password);
      api.setAuthToken(result.accessToken);
      useAuthStore.setState({ user: result.user, ready: true });
    } catch (error) {
      // Let error propagate to UI
      throw error;
    }
  }, []);
```

- [ ] **Step 2: Update logout to clear auth state**

Ensure logout clears everything:

```typescript
  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      // Ignore logout errors
    } finally {
      api.setAuthToken(null);
      useAuthStore.setState({ user: null, ready: true });
      // Redirect to login
      window.location.href = '/login';
    }
  }, []);
```

- [ ] **Step 3: Add global error listener for AUTH_REQUIRED**

Add after the login callback (around line 43):

```typescript
  // Global handler for auth expiry
  useEffect(() => {
    const handleAuthError = (event: ErrorEvent) => {
      if (event.error?.code === 'AUTH_REQUIRED') {
        api.setAuthToken(null);
        useAuthStore.setState({ user: null, ready: true });
        window.location.href = '/login';
      }
    };

    window.addEventListener('error', handleAuthError);
    return () => window.removeEventListener('error', handleAuthError);
  }, []);
```

- [ ] **Step 4: Commit**

```bash
git add src/services/auth.ts
git commit -m "feat(auth): handle AUTH_REQUIRED and refresh failures"
```

---

## Task 4: Add Logout Button to Shell

**Files:**
- Modify: `src/components/Shell.tsx`

- [ ] **Step 1: Read current Shell component**

Run:
```bash
cat src/components/Shell.tsx
```

Expected: Find navigation structure and user display

- [ ] **Step 2: Add logout button to navigation**

Locate the user menu or navigation area and add logout button. Example structure:

```typescript
import { useAuth } from '@services/auth';

export function Shell({ children }: { children: React.ReactNode }) {
  const { user, logout } = useAuth();

  const handleLogout = async () => {
    if (confirm('确定要退出登录吗？')) {
      await logout();
    }
  };

  return (
    <div className="shell">
      <nav className="shell-nav">
        {/* ... existing nav items ... */}
        {user && (
          <button 
            onClick={handleLogout}
            className="logout-btn"
            title="退出登录"
          >
            退出
          </button>
        )}
      </nav>
      <main>{children}</main>
    </div>
  );
}
```

- [ ] **Step 3: Add minimal styles**

If Shell has a companion CSS file, add:

```css
.logout-btn {
  padding: 0.5rem 1rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.875rem;
}

.logout-btn:hover {
  background: var(--hover-bg);
}
```

- [ ] **Step 4: Commit**

```bash
git add src/components/Shell.tsx
git commit -m "feat(shell): add logout button to navigation"
```

---

## Task 5: Write Tests for Refresh Flow

**Files:**
- Modify: `src/services/__tests__/auth.test.ts`

- [ ] **Step 1: Add test for AUTH_REQUIRED handling**

Add test after existing tests:

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useAuth, resetAuthForTests } from '../auth';
import * as api from '@shared/api/client';

describe('useAuth refresh flow', () => {
  beforeEach(() => {
    resetAuthForTests();
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('redirects to login on AUTH_REQUIRED', async () => {
    const mockReplace = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '', replace: mockReplace },
      writable: true,
    });

    vi.spyOn(api, 'getCurrentUser').mockRejectedValue({
      code: 'AUTH_REQUIRED',
      message: '会话已过期',
    });

    const { result } = renderHook(() => useAuth());

    await waitFor(() => {
      expect(result.current.isAuthenticated).toBe(false);
    });

    // Trigger error event
    const errorEvent = new ErrorEvent('error', {
      error: { code: 'AUTH_REQUIRED' },
    });
    window.dispatchEvent(errorEvent);

    await waitFor(() => {
      expect(window.location.href).toBe('/login');
    });
  });

  it('clears token on logout', async () => {
    vi.spyOn(api, 'logout').mockResolvedValue(undefined);
    const setTokenSpy = vi.spyOn(api, 'setAuthToken');
    
    const mockReplace = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '', replace: mockReplace },
      writable: true,
    });

    const { result } = renderHook(() => useAuth());

    await result.current.logout();

    expect(setTokenSpy).toHaveBeenCalledWith(null);
    expect(window.location.href).toBe('/login');
  });
});
```

- [ ] **Step 2: Run tests**

```bash
cd apps/meeting-web
npm test src/services/__tests__/auth.test.ts
```

Expected: PASS (2 new tests)

- [ ] **Step 3: Commit**

```bash
git add src/services/__tests__/auth.test.ts
git commit -m "test(auth): add refresh flow tests"
```

---

## Task 6: Write Tests for 401 Interceptor

**Files:**
- Create: `src/shared/api/__tests__/client.test.ts`

- [ ] **Step 1: Create test file with 401 intercept tests**

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as client from '../client';

describe('API client 401 interceptor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn();
    // Mock cookie
    Object.defineProperty(document, 'cookie', {
      writable: true,
      value: 'XSRF-TOKEN=test-csrf-token',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('retries request after successful refresh on 401', async () => {
    client.setAuthToken('old-token');

    // First call returns 401
    (global.fetch as any).mockResolvedValueOnce({
      status: 401,
      ok: false,
      json: async () => ({
        success: false,
        error: { code: 'AUTH_REQUIRED', message: 'Unauthorized' },
      }),
    });

    // Refresh succeeds
    (global.fetch as any).mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: { accessToken: 'new-token', expiresAt: '2026-06-14T00:00:00Z' },
      }),
    });

    // Retry succeeds
    (global.fetch as any).mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: { taskId: 'task_123' },
      }),
    });

    const result = await client.getCurrentUser();
    
    expect(global.fetch).toHaveBeenCalledTimes(3);
    expect(result).toBeDefined();
  });

  it('throws AUTH_REQUIRED after refresh failure', async () => {
    client.setAuthToken('old-token');

    // First call returns 401
    (global.fetch as any).mockResolvedValueOnce({
      status: 401,
      ok: false,
      json: async () => ({
        success: false,
        error: { code: 'AUTH_REQUIRED', message: 'Unauthorized' },
      }),
    });

    // Refresh fails
    (global.fetch as any).mockResolvedValueOnce({
      status: 401,
      ok: false,
      json: async () => ({
        success: false,
        error: { code: 'REFRESH_TOKEN_INVALID', message: 'Refresh token expired' },
      }),
    });

    await expect(client.getCurrentUser()).rejects.toMatchObject({
      code: 'AUTH_REQUIRED',
      retryable: false,
      status: 401,
    });

    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  it('uses single-flight refresh for concurrent 401s', async () => {
    client.setAuthToken('old-token');

    // Both calls return 401
    (global.fetch as any).mockResolvedValueOnce({
      status: 401,
      ok: false,
      json: async () => ({
        success: false,
        error: { code: 'AUTH_REQUIRED', message: 'Unauthorized' },
      }),
    });

    (global.fetch as any).mockResolvedValueOnce({
      status: 401,
      ok: false,
      json: async () => ({
        success: false,
        error: { code: 'AUTH_REQUIRED', message: 'Unauthorized' },
      }),
    });

    // Single refresh
    (global.fetch as any).mockResolvedValueOnce({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: { accessToken: 'new-token', expiresAt: '2026-06-14T00:00:00Z' },
      }),
    });

    // Both retries succeed
    (global.fetch as any).mockResolvedValue({
      status: 200,
      ok: true,
      json: async () => ({
        success: true,
        data: { user: { userId: 'u1' } },
      }),
    });

    const [result1, result2] = await Promise.all([
      client.getCurrentUser(),
      client.getCurrentUser(),
    ]);

    expect(result1).toBeDefined();
    expect(result2).toBeDefined();
    // 2 x 401 + 1 x refresh + 2 x retry = 5 total
    expect(global.fetch).toHaveBeenCalledTimes(5);
  });
});
```

- [ ] **Step 2: Run tests**

```bash
npm test src/shared/api/__tests__/client.test.ts
```

Expected: PASS (3 tests)

- [ ] **Step 3: Commit**

```bash
git add src/shared/api/__tests__/client.test.ts
git commit -m "test(api): add 401 interceptor tests"
```

---

## Task 7: Integration Smoke Test

**Files:**
- None (manual testing)

- [ ] **Step 1: Start dev server**

```bash
npm run dev
```

Expected: Server starts on http://localhost:5173

- [ ] **Step 2: Test refresh flow**

1. Login with admin/admin123
2. Open DevTools → Application → Cookies
3. Verify `REFRESH_TOKEN` (HttpOnly) and `XSRF-TOKEN` exist
4. Copy access token from memory (console: `localStorage` should be empty)
5. Wait for token to expire (or manually expire in backend)
6. Make an API call (navigate to /meetings)
7. Verify automatic refresh happens (Network tab shows /auth/refresh)
8. Verify original request retries and succeeds

- [ ] **Step 3: Test logout button**

1. Click logout button in Shell navigation
2. Verify confirmation dialog appears
3. Confirm logout
4. Verify redirect to /login
5. Verify cookies cleared (DevTools → Application → Cookies)

- [ ] **Step 4: Test refresh failure**

1. Login
2. Delete `REFRESH_TOKEN` cookie manually (DevTools)
3. Make an API call after token expires
4. Verify redirect to /login with "会话已过期" message

- [ ] **Step 5: Document smoke test results**

Create `docs/superpowers/2026-06-13-p3-c3-smoke-test.md`:

```markdown
# C3 401 Refresh Flow - Smoke Test Results

**Date:** 2026-06-13  
**Tester:** [Your Name]

## Test Cases

### TC1: Automatic Refresh on 401
- **Status:** ✅ PASS / ❌ FAIL
- **Notes:** ...

### TC2: Logout Button
- **Status:** ✅ PASS / ❌ FAIL  
- **Notes:** ...

### TC3: Refresh Failure Handling
- **Status:** ✅ PASS / ❌ FAIL
- **Notes:** ...

### TC4: Single-Flight Refresh
- **Status:** ✅ PASS / ❌ FAIL
- **Notes:** ...
```

- [ ] **Step 6: Final commit**

```bash
git add docs/superpowers/2026-06-13-p3-c3-smoke-test.md
git commit -m "docs: add C3 smoke test results"
```

---

## Task 8: Update Todo

**Files:**
- Modify: `todo.md:502`

- [ ] **Step 1: Mark C3 complete**

Change line 502 from:

```markdown
- [ ] C3：401 单飞 refresh（X-CSRF-Token）+ 失败清 token 回登录页 + Shell 登出按钮。
```

To:

```markdown
- [x] C3：401 单飞 refresh（X-CSRF-Token）+ 失败清 token 回登录页 + Shell 登出按钮。
```

- [ ] **Step 2: Commit**

```bash
git add todo.md
git commit -m "docs: mark P3 C3 complete"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-13-p3-c3-auth-refresh.md`. Two execution options:

**1. Subagent-Driven (recommended)** - Dispatch fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
