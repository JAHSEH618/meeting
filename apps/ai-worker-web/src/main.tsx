import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import App from "@/App";
import { createQueryClient } from "@/shared/queries/queryClient";
import "@/styles.css";

const rootEl = document.getElementById("root");
if (!rootEl) throw new Error("missing #root element");

/**
 * Phase J runtime config — fetched once at boot, before React mounts.
 *
 * Why we don't use a ``<script>`` tag: Vite can't bundle a non-module
 * classic script and surfaces a build warning every time we recompile.
 * Fetching the config from a JSON endpoint keeps the bootstrap inside a
 * single module (no warning), lets FastAPI swap per-environment values
 * without rebuilding the SPA, and falls back gracefully when the endpoint
 * is unreachable (dev with no backend, network blip, etc.) — the SPA then
 * inherits ``VITE_AUTH_LOGIN_URL`` or uses its own /workstation/login page.
 */
async function loadRuntimeConfig(): Promise<void> {
  try {
    const response = await fetch(`${import.meta.env.BASE_URL}runtime-config.json`, {
      cache: "no-cache",
    });
    if (!response.ok) return;
    const config = (await response.json()) as Window["__WORKSTATION_CONFIG__"];
    window.__WORKSTATION_CONFIG__ = config ?? {};
  } catch {
    // Network / parse failure — leave undefined so shared/auth/store.ts
    // falls through to build-time config or the local workstation login page.
    // We log to console so an operator can spot misconfigured ingresses without breaking UX.
    console.warn("workstation runtime-config.json unreachable; using SPA defaults");
  }
}

await loadRuntimeConfig();

const queryClient = createQueryClient();

createRoot(rootEl).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter
        basename="/workstation"
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <App />
      </BrowserRouter>
      {import.meta.env.DEV ? <ReactQueryDevtools buttonPosition="bottom-right" /> : null}
    </QueryClientProvider>
  </StrictMode>,
);
