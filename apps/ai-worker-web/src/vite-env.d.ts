/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Absolute or relative URL to the Java login flow. Build-time fallback
   * when no runtime config is injected (see ``window.__WORKSTATION_CONFIG__``
   * below for the recommended path).
   */
  readonly VITE_AUTH_LOGIN_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/**
 * Runtime config injected by ai-worker FastAPI at
 * ``/workstation/runtime-config.json`` (fetched by main.tsx at bootstrap
 * and assigned to ``window.__WORKSTATION_CONFIG__`` before React mounts).
 * Prefer this over ``VITE_AUTH_LOGIN_URL`` for K8s deployments where the
 * Java login URL changes per environment — switching it does not require
 * a SPA rebuild.
 */
interface WorkstationRuntimeConfig {
  /**
   * Where to bounce the user when the SPA gets a 401. Absolute URL when the
   * Java login lives on a different host than the workstation Ingress.
   */
  authLoginUrl?: string;
}

interface Window {
  __WORKSTATION_CONFIG__?: WorkstationRuntimeConfig;
}

