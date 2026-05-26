import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "path";

export default defineConfig(({ mode }) => {
  // Load environment variables based on the current mode
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.VITE_API_TARGET || "http://localhost:8080";

  return {
    plugins: [react()],
    resolve: {
      alias: {
        "@shared": resolve(__dirname, "src/shared"),
        "@features": resolve(__dirname, "src/features"),
        "@services": resolve(__dirname, "src/services"),
      },
    },
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target,
          changeOrigin: true,
        },
      },
    },
    test: {
      globals: true,
      environment: "jsdom",
      setupFiles: ["./src/test-setup.ts"],
      // Playwright specs live under e2e/ and import @playwright/test;
      // exclude them from vitest so `npm test` stays unit/integration only.
      exclude: ["**/node_modules/**", "**/dist/**", "e2e/**"],
    },
  };
});
