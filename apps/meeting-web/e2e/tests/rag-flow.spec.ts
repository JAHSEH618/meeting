import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync } from "node:fs";
import { expect, test } from "@playwright/test";

const __dirname_local = dirname(fileURLToPath(import.meta.url));

/**
 * final-check.md B4 — Playwright coverage for the RAG main flow.
 *
 * Two variants:
 *
 * 1. **Scope-less query** — independent of any uploaded audio. Asks a
 *    question that the model can answer from "no information found"
 *    fallback; we just assert the UI surfaces an answer card with a
 *    coverage badge, so the rendering pipeline is exercised end-to-end.
 *    Always runs.
 *
 * 2. **Meeting-scope query** — picks the meeting created in main-flow
 *    and asks a question. Citation deep-link clicks back to the
 *    transcript page. Gated on the same E2E_AUDIO_FIXTURE as main-flow;
 *    without the fixture there's no transcript to cite.
 */

const DEMO_USER = process.env.E2E_USER ?? "admin";
const DEMO_PASS = process.env.E2E_PASS ?? "admin123";
const AUDIO_FIXTURE = process.env.E2E_AUDIO_FIXTURE
  ?? resolve(__dirname_local, "..", "fixtures", "sample-30s.wav");

async function login(page: import("@playwright/test").Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel(/账号|用户名|username/i).fill(DEMO_USER);
  await page.getByLabel(/密码|password/i).fill(DEMO_PASS);
  await page.getByRole("button", { name: /登录|sign in/i }).click();
  await expect(page).toHaveURL(/\/meetings$/);
}

test("rag scope-less question renders an answer card with coverage badge", async ({ page }) => {
  await login(page);
  await page.goto("/rag");

  // DIAGNOSTIC: Check what's on the page
  const pageContent = await page.content();
  const hasElement = await page.locator('#rag-question').count() > 0;
  if (!hasElement) {
    console.error(`[E2E DIAGNOSTIC] #rag-question not found. Page content sample:`);
    console.error(pageContent.substring(0, 1000));
    const url = page.url();
    console.error(`  Current URL: ${url}`);
  }

  // Wait for the lazy-loaded RagPage to render by checking for the question input
  await page.waitForSelector('#rag-question', { state: 'visible' });

  await page.getByLabel(/问题|question/i).fill("最近的会议讨论了哪些主题?");
  await page.getByRole("button", { name: /提问|ask|submit/i }).click();

  // The answer card always renders, even for the "no information"
  // fallback. Coverage badge is the load-bearing assertion — it tells
  // the user whether the response is grounded in the transcript or
  // the broader knowledge base.
  await expect(page.getByLabel(/rag-answer/i).first())
    .toBeVisible({ timeout: 60_000 });
  await expect(page.getByLabel(/rag-coverage/i)).toBeVisible();
});

test("rag citation deep-links into the transcript", async ({ page, context }) => {
  test.skip(!existsSync(AUDIO_FIXTURE),
    `audio fixture not found at ${AUDIO_FIXTURE}; citation deep-link needs a real transcript`);

  await login(page);

  // Pre-condition: there is at least one meeting with a transcript.
  // We don't re-run upload here (main-flow already covers it); instead
  // we open the meetings list and click into whichever has SUCCEEDED.
  await page.goto("/meetings");
  await expect(page.getByText(/SUCCEEDED|已完成/i).first()).toBeVisible({ timeout: 60_000 });
  // Open the first SUCCEEDED meeting's RAG page.
  await page.goto("/rag");

  // Wait for the lazy-loaded RagPage to render by checking for the question input
  await page.waitForSelector('#rag-question', { state: 'visible' });

  await page.getByLabel(/问题|question/i).fill("会议主要讨论了什么");
  await page.getByRole("button", { name: /提问|ask|submit/i }).click();
  await expect(page.getByLabel(/rag-answer/i).first()).toBeVisible({ timeout: 60_000 });

  // A meeting-scope citation links to /meetings/<id>/transcript?segmentId=...
  // — click it and assert the transcript page loads.
  const citation = page.locator('[aria-label^="citation-"]').first();
  await expect(citation).toBeVisible({ timeout: 10_000 });
  const link = citation.getByRole("link", { name: /跳转到转写片段|transcript/i });
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(/\/meetings\/m_[a-z0-9]+\/transcript\?/);
});
