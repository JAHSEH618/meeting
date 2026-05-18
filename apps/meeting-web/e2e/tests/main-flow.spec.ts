import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

const __dirname_local = dirname(fileURLToPath(import.meta.url));

/**
 * Phase 8.7.2 — golden-path coverage from login through PDF export.
 *
 * Pre-conditions:
 *   1. `docker compose --profile full-stack up -d` is running.
 *   2. A demo user (default `demo@meeting.local` / `demo`) is bootstrapped
 *      by the meeting-api seed migration.
 *   3. LibreOffice + Noto CJK are installed in the meeting-api container
 *      (the prod Dockerfile bundles them).
 *
 * The spec deliberately keeps timing wide because the meeting-api
 * callback loop is asynchronous; we wait for the page to surface a
 * stable terminal state rather than poll the API directly.
 *
 * The "full upload→SSE→RAG→PDF" scenario is gated on
 * `E2E_AUDIO_FIXTURE` pointing at a ≤30 s WAV — without it, ASR
 * runtime stays in deterministic-fake mode (no real callback) so the
 * pipeline never produces a transcript and the spec would fail
 * spuriously. CI mounts a fixture at `e2e/fixtures/sample-30s.wav`.
 */

const DEMO_USER = process.env.E2E_USER ?? "demo@meeting.local";
const DEMO_PASS = process.env.E2E_PASS ?? "demo";
const AUDIO_FIXTURE = process.env.E2E_AUDIO_FIXTURE
  ?? resolve(__dirname_local, "..", "fixtures", "sample-30s.wav");

async function login(page: import("@playwright/test").Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel(/用户名|username/i).fill(DEMO_USER);
  await page.getByLabel(/密码|password/i).fill(DEMO_PASS);
  await page.getByRole("button", { name: /登录|sign in/i }).click();
  await expect(page).toHaveURL(/\/meetings$/);
}

async function createMeeting(
  page: import("@playwright/test").Page,
  title: string,
  securityLevel: "INTERNAL" | "CONFIDENTIAL" = "INTERNAL"
): Promise<string> {
  await page.getByRole("link", { name: /新建会议|create/i }).click();
  await page.getByLabel(/标题|title/i).fill(title);
  await page.getByLabel(/语言|language/i).selectOption("zh");
  await page.getByLabel(/安全等级|security/i).selectOption(securityLevel);
  await page.getByRole("button", { name: /创建|submit/i }).click();
  await expect(page).toHaveURL(/\/meetings\/mtg_[a-z0-9]+/);
  return page.url();
}

test("login → create meeting → transcript / exports pages render", async ({ page }) => {
  await login(page);
  const meetingUrl = await createMeeting(page, `E2E smoke ${Date.now()}`);

  // Verify transcript / exports pages render the "no content yet"
  // fallbacks; the audio upload pipeline is exercised more
  // thoroughly by export-pdf-smoke.sh, which can validate the
  // actual PDF bytes via pdftotext.
  await page.goto(meetingUrl + "/transcript");
  await expect(page.getByRole("heading", { name: /转录|transcript/i })).toBeVisible();

  await page.goto(meetingUrl + "/exports");
  await expect(page.getByRole("heading", { name: /导出|export/i })).toBeVisible();
});

test("security-level CONFIDENTIAL automatic LLM is fail-closed", async ({ page }) => {
  await login(page);
  await createMeeting(page, `E2E confidential ${Date.now()}`, "CONFIDENTIAL");

  // The minutes regeneration endpoint should hand back the stable
  // SECURITY_LEVEL_BLOCKED copy — the frontend renders it verbatim.
  const minutesUrl = page.url() + "/minutes";
  await page.goto(minutesUrl);
  await expect(page.getByText(/不支持该安全等级|SECURITY_LEVEL_BLOCKED/i))
    .toBeVisible({ timeout: 30_000 });
});

test("upload → SSE → transcript → RAG → PDF export", async ({ page }) => {
  test.skip(!existsSync(AUDIO_FIXTURE),
    `audio fixture not found at ${AUDIO_FIXTURE}; set E2E_AUDIO_FIXTURE or stage e2e/fixtures/sample-30s.wav`);

  await login(page);
  const meetingUrl = await createMeeting(page, `E2E full ${Date.now()}`);

  // ── Audio upload ──────────────────────────────────────────
  await page.goto(meetingUrl + "/audio");
  const fileInput = page.getByLabel(/选择音频|audio file|upload/i).first();
  await fileInput.setInputFiles(AUDIO_FIXTURE);
  // The upload page kicks off multipart upload + auto-redirects to the
  // task progress page on completion. Wait up to 5 min for the worker
  // to finish (deterministic ASR is fast; real ASR may take longer).
  await page.waitForURL(/\/meetings\/mtg_[a-z0-9]+\/tasks\/task_[a-z0-9]+/, { timeout: 300_000 });

  // ── Task progress: wait for terminal status via the SSE-driven UI ──
  await expect(page.getByText(/SUCCEEDED|PARTIAL_SUCCEEDED|已完成/i))
    .toBeVisible({ timeout: 300_000 });

  // ── Transcript visible ────────────────────────────────────
  await page.goto(meetingUrl + "/transcript");
  await expect(page.getByText(/SPEAKER_/i)).toBeVisible({ timeout: 30_000 });

  // ── RAG: ask a question and check citation appears ────────
  await page.goto("/rag");
  await page.getByLabel(/问题|question/i).fill("会议主要讨论了什么");
  await page.getByRole("button", { name: /提问|ask|submit/i }).click();
  await expect(page.getByLabel(/rag-answer-body/i))
    .toBeVisible({ timeout: 60_000 });
  // Coverage badge always shown.
  await expect(page.getByLabel(/rag-coverage/i)).toBeVisible();

  // ── PDF export ────────────────────────────────────────────
  await page.goto(meetingUrl + "/exports");
  await page.getByLabel(/格式|format/i).selectOption("PDF");
  await page.getByRole("button", { name: /创建|create|export/i }).click();
  // Wait for the new row to flip to "已完成".
  await expect(page.getByText(/已完成|SUCCEEDED/i).first())
    .toBeVisible({ timeout: 180_000 });
  // Download button is enabled once SUCCEEDED + not revoked.
  await expect(page.getByRole("link", { name: /下载|download/i }).first())
    .toBeVisible();
});
