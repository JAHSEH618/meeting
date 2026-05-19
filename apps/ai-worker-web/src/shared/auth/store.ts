/**
 * In-memory access token store. Per spec we MUST NOT persist to localStorage
 * or sessionStorage. Refresh flow is handled by Java via HttpOnly cookie +
 * X-CSRF-Token; the SPA only holds the short-lived bearer token in memory.
 */

type Listener = (token: string | null) => void;

class AuthStore {
  private token: string | null = null;
  private listeners = new Set<Listener>();

  get(): string | null {
    return this.token;
  }

  set(token: string | null): void {
    this.token = token;
    for (const fn of this.listeners) fn(token);
  }

  clear(): void {
    this.set(null);
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
}

export const authStore = new AuthStore();

/** SPA login bounce — read access_token from URL fragment after Java login. */
export function consumeFragmentToken(hash: string = window.location.hash): boolean {
  if (!hash || !hash.startsWith("#")) return false;
  const params = new URLSearchParams(hash.slice(1));
  const token = params.get("access_token");
  if (!token) return false;
  authStore.set(token);
  // Clean the URL so the fragment doesn't leak into history.
  history.replaceState(null, "", window.location.pathname + window.location.search);
  return true;
}

export function redirectToLogin(): void {
  const redirect = encodeURIComponent(window.location.href);
  window.location.assign(`/auth/login?redirect=${redirect}`);
}
