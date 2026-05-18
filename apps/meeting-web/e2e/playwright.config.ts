import { defineConfig, devices } from "@playwright/test";

/**
 * Phase 8.7 — Playwright E2E configuration.
 *
 * Runs against a full-stack docker-compose (postgres + rabbitmq +
 * minio + meeting-api + meeting-web). See `npm run e2e` in
 * package.json or `infra/meeting-infra/docker/compose/docker-compose.yml`
 * with the `full-stack` profile.
 *
 * Targets the requirements in 8.7.3:
 * - retries: 1 on CI to absorb flake in the worker callback chain
 * - 10-minute wall-clock budget (we use a fixed timeout per test
 *   instead of a global one so a stalled scenario doesn't drag the
 *   whole suite over budget)
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,            // shared meeting-api state across tests
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [["html", { open: "never" }], ["list"]]
    : "list",
  timeout: 120_000,                // per-test budget
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
