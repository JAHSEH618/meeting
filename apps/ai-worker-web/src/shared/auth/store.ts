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

function configuredLoginUrl(): string | null {
  // Resolution order:
  //   1. window.__WORKSTATION_CONFIG__.authLoginUrl  — runtime, injected by
  //      FastAPI from AI_WORKER_AUTH_LOGIN_URL so prod / staging can flip
  //      the URL without rebuilding the SPA image.
  //   2. VITE_AUTH_LOGIN_URL                          — build-time fallback
  //      for setups that don't run the workstation behind ai-worker.
  // See infra/meeting-infra/k8s/base/ai-worker — the workstation Ingress
  // only routes /admin and /workstation, so any K8s deploy needs one of these.
  const runtimeUrl = window.__WORKSTATION_CONFIG__?.authLoginUrl?.trim();
  if (runtimeUrl) return runtimeUrl;
  const buildUrl = import.meta.env.VITE_AUTH_LOGIN_URL?.trim();
  return buildUrl || null;
}

const LOCAL_LOGIN_PATH = "/workstation/login";

function currentPathname(currentUrl: string): string {
  try {
    return new URL(currentUrl, window.location.origin).pathname;
  } catch {
    return "";
  }
}

export function redirectToLogin(): boolean {
  const loginUrl = configuredLoginUrl();
  const currentUrl = window.location.href;
  const pathname = currentPathname(currentUrl);
  if (!loginUrl && pathname === LOCAL_LOGIN_PATH) {
    console.warn("Already on local workstation login page. Stopping redirect.");
    return false;
  }

  const redirect = encodeURIComponent(currentUrl);
  if (!loginUrl) {
    window.location.assign(`${LOCAL_LOGIN_PATH}?redirect=${redirect}`);
    return true;
  }

  // Prevent infinite redirect loops if we are already on the target login page.
  if (currentUrl.includes(loginUrl)) {
    console.warn("Already on login page or redirect loop detected. Stopping redirect.");
    return false;
  }

  const sep = loginUrl.includes("?") ? "&" : "?";
  window.location.assign(`${loginUrl}${sep}redirect=${redirect}`);
  return true;
}
