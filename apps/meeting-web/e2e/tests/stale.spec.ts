import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

const __dirname_local = dirname(fileURLToPath(import.meta.url));

/**
 * Phase 8.7.2.c — STALE branch.
 *
 * After a transcript edit, downstream artefacts (minutes / action
 * items / decisions / risks / RAG chunks / exports) must surface a
 * STALE banner so the user knows the AI output is no longer in sync
 * with the source-of-truth transcript.
 *
 * Pre-conditions for the full path:
 *   1. The audio upload pipeline has produced at least one transcript
 *      segment (we use the same E2E_AUDIO_FIXTURE as main-flow).
 *   2. Minutes have been generated for the meeting so the STALE
 *      indicator has something to mark stale.
 *
 * Skips when the fixture isn't available — without a real transcript
 * the edit form has nothing to mutate.
 */

const DEMO_USER = process.env.E2E_USER ?? "demo@meeting.local";
const DEMO_PASS = process.env.E2E_PASS ?? "demo";
const AUDIO_FIXTURE = process.env.E2E_AUDIO_FIXTURE
  ?? resolve(__dirname_local, "..", "fixtures", "sample-30s.wav");

test("transcript edit surfaces STALE banner on minutes", async ({ page }) => {
  test.skip(!existsSync(AUDIO_FIXTURE),
    `audio fixture not found at ${AUDIO_FIXTURE}; STALE branch needs a real transcript`);

  // ── Login + create meeting + upload ────────────────────────
  await page.goto("/login");
  await page.getByLabel(/用户名|username/i).fill(DEMO_USER);
  await page.getByLabel(/密码|password/i).fill(DEMO_PASS);
  await page.getByRole("button", { name: /登录|sign in/i }).click();
  await expect(page).toHaveURL(/\/meetings$/);

  await page.getByRole("link", { name: /新建会议|create/i }).click();
  await page.getByLabel(/标题|title/i).fill(`E2E stale ${Date.now()}`);
  await page.getByLabel(/安全等级|security/i).selectOption("INTERNAL");
  await page.getByRole("button", { name: /创建|submit/i }).click();
  await expect(page).toHaveURL(/\/meetings\/mtg_[a-z0-9]+/);
  const meetingUrl = page.url();

  await page.goto(meetingUrl + "/audio");
  await page.getByLabel(/选择音频|audio file|upload/i).first()
    .setInputFiles(AUDIO_FIXTURE);
  await page.waitForURL(/\/tasks\/task_/, { timeout: 300_000 });
  await expect(page.getByText(/SUCCEEDED|PARTIAL_SUCCEEDED|已完成/i))
    .toBeVisible({ timeout: 300_000 });

  // ── Edit a transcript segment ──────────────────────────────
  await page.goto(meetingUrl + "/transcript");
  await page.getByRole("button", { name: /编辑|edit/i }).first().click();
  const editor = page.getByRole("textbox").first();
  await editor.fill("E2E stale-test edit content " + Date.now());
  await page.getByRole("button", { name: /保存|save/i }).click();

  // ── Verify STALE banner shows on minutes ───────────────────
  await page.goto(meetingUrl + "/minutes");
  await expect(page.getByText(/STALE|内容已过期|需要重新生成/i))
    .toBeVisible({ timeout: 30_000 });
});