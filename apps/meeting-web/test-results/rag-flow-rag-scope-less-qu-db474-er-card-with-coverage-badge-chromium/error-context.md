# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: rag-flow.spec.ts >> rag scope-less question renders an answer card with coverage badge
- Location: e2e/tests/rag-flow.spec.ts:38:1

# Error details

```
Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:5173/login
Call log:
  - navigating to "http://localhost:5173/login", waiting until "load"

```

# Test source

```ts
  1  | import { dirname, resolve } from "node:path";
  2  | import { fileURLToPath } from "node:url";
  3  | import { existsSync } from "node:fs";
  4  | import { expect, test } from "@playwright/test";
  5  | 
  6  | const __dirname_local = dirname(fileURLToPath(import.meta.url));
  7  | 
  8  | /**
  9  |  * final-check.md B4 — Playwright coverage for the RAG main flow.
  10 |  *
  11 |  * Two variants:
  12 |  *
  13 |  * 1. **Scope-less query** — independent of any uploaded audio. Asks a
  14 |  *    question that the model can answer from "no information found"
  15 |  *    fallback; we just assert the UI surfaces an answer card with a
  16 |  *    coverage badge, so the rendering pipeline is exercised end-to-end.
  17 |  *    Always runs.
  18 |  *
  19 |  * 2. **Meeting-scope query** — picks the meeting created in main-flow
  20 |  *    and asks a question. Citation deep-link clicks back to the
  21 |  *    transcript page. Gated on the same E2E_AUDIO_FIXTURE as main-flow;
  22 |  *    without the fixture there's no transcript to cite.
  23 |  */
  24 | 
  25 | const DEMO_USER = process.env.E2E_USER ?? "admin";
  26 | const DEMO_PASS = process.env.E2E_PASS ?? "admin123";
  27 | const AUDIO_FIXTURE = process.env.E2E_AUDIO_FIXTURE
  28 |   ?? resolve(__dirname_local, "..", "fixtures", "sample-30s.wav");
  29 | 
  30 | async function login(page: import("@playwright/test").Page): Promise<void> {
> 31 |   await page.goto("/login");
     |              ^ Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:5173/login
  32 |   await page.getByLabel(/账号|用户名|username/i).fill(DEMO_USER);
  33 |   await page.getByLabel(/密码|password/i).fill(DEMO_PASS);
  34 |   await page.getByRole("button", { name: /登录|sign in/i }).click();
  35 |   await expect(page).toHaveURL(/\/meetings$/);
  36 | }
  37 | 
  38 | test("rag scope-less question renders an answer card with coverage badge", async ({ page }) => {
  39 |   await login(page);
  40 |   await page.goto("/rag");
  41 | 
  42 |   // DIAGNOSTIC: Check what's on the page
  43 |   const pageContent = await page.content();
  44 |   const hasElement = await page.locator('#rag-question').count() > 0;
  45 |   if (!hasElement) {
  46 |     console.error(`[E2E DIAGNOSTIC] #rag-question not found. Page content sample:`);
  47 |     console.error(pageContent.substring(0, 1000));
  48 |     const url = page.url();
  49 |     console.error(`  Current URL: ${url}`);
  50 |   }
  51 | 
  52 |   // Wait for the lazy-loaded RagPage to render by checking for the question input
  53 |   await page.waitForSelector('#rag-question', { state: 'visible' });
  54 | 
  55 |   await page.getByLabel(/问题|question/i).fill("最近的会议讨论了哪些主题?");
  56 |   await page.getByRole("button", { name: /提问|ask|submit/i }).click();
  57 | 
  58 |   // The answer card always renders, even for the "no information"
  59 |   // fallback. Coverage badge is the load-bearing assertion — it tells
  60 |   // the user whether the response is grounded in the transcript or
  61 |   // the broader knowledge base.
  62 |   await expect(page.getByLabel(/rag-answer/i).first())
  63 |     .toBeVisible({ timeout: 60_000 });
  64 |   await expect(page.getByLabel(/rag-coverage/i)).toBeVisible();
  65 | });
  66 | 
  67 | test("rag citation deep-links into the transcript", async ({ page, context }) => {
  68 |   test.skip(!existsSync(AUDIO_FIXTURE),
  69 |     `audio fixture not found at ${AUDIO_FIXTURE}; citation deep-link needs a real transcript`);
  70 | 
  71 |   await login(page);
  72 | 
  73 |   // Pre-condition: there is at least one meeting with a transcript.
  74 |   // We don't re-run upload here (main-flow already covers it); instead
  75 |   // we open the meetings list and click into whichever has SUCCEEDED.
  76 |   await page.goto("/meetings");
  77 |   await expect(page.getByText(/SUCCEEDED|已完成/i).first()).toBeVisible({ timeout: 60_000 });
  78 |   // Open the first SUCCEEDED meeting's RAG page.
  79 |   await page.goto("/rag");
  80 | 
  81 |   // Wait for the lazy-loaded RagPage to render by checking for the question input
  82 |   await page.waitForSelector('#rag-question', { state: 'visible' });
  83 | 
  84 |   await page.getByLabel(/问题|question/i).fill("会议主要讨论了什么");
  85 |   await page.getByRole("button", { name: /提问|ask|submit/i }).click();
  86 |   await expect(page.getByLabel(/rag-answer/i).first()).toBeVisible({ timeout: 60_000 });
  87 | 
  88 |   // A meeting-scope citation links to /meetings/<id>/transcript?segmentId=...
  89 |   // — click it and assert the transcript page loads.
  90 |   const citation = page.locator('[aria-label^="citation-"]').first();
  91 |   await expect(citation).toBeVisible({ timeout: 10_000 });
  92 |   const link = citation.getByRole("link", { name: /跳转到转写片段|transcript/i });
  93 |   await expect(link).toBeVisible();
  94 |   await link.click();
  95 |   await expect(page).toHaveURL(/\/meetings\/m_[a-z0-9]+\/transcript\?/);
  96 | });
  97 | 
```