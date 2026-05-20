/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Absolute or relative URL to the Java login flow. When unset, the SPA
   * redirects to ``/auth/login`` on the same host — which only works when
   * the workstation SPA is reverse-proxied behind a host that also fronts
   * meeting-api. Set this to the Java public URL when the workstation
   * lives on a separate host (the K8s Ingress for ai-worker only routes
   * ``/admin`` + ``/workstation``).
   */
  readonly VITE_AUTH_LOGIN_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
