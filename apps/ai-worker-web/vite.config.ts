import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

const meetingApiTarget = process.env.VITE_MEETING_API_TARGET ?? "http://10.9.50.179:8080";

export default defineConfig({
  // The workstation SPA is served under /workstation/ both in dev and
  // in prod (FastAPI mounts the StaticFiles at /workstation/). Anything
  // other than "/workstation/" causes the built index.html to emit
  // ``/assets/...`` absolute paths that 404 once mounted under the
  // sub-path; keep this in sync with ``BrowserRouter basename`` and the
  // K8s Ingress prefix in infra/meeting-infra/k8s/base/ai-worker.
  base: "/workstation/",
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5174,
    proxy: {
      "/admin": "http://localhost:8090",
      "/api": meetingApiTarget,
    },
  },
  build: {
    target: "es2022",
    sourcemap: true,
    rollupOptions: {
      output: {
        // Keep route-level split so first-screen JS stays under 200KB gzip (todo D5.3).
        manualChunks: {
          react: ["react", "react-dom"],
          markdown: ["react-markdown", "remark-gfm", "rehype-sanitize"],
        },
      },
    },
  },
});
