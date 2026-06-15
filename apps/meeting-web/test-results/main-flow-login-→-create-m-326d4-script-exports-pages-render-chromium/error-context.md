# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: main-flow.spec.ts >> login → create meeting → transcript / exports pages render
- Location: e2e/tests/main-flow.spec.ts:53:1

# Error details

```
Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:5173/login
Call log:
  - navigating to "http://localhost:5173/login", waiting until "load"

```

# Test source

```ts
  1   | import { existsSync } from "node:fs";
  2   | import { dirname, resolve } from "node:path";
  3   | import { fileURLToPath } from "node:url";
  4   | import { expect, test } from "@playwright/test";
  5   | 
  6   | const __dirname_local = dirname(fileURLToPath(import.meta.url));
  7   | 
  8   | /**
  9   |  * Phase 8.7.2 — golden-path coverage from login through PDF export.
  10  |  *
  11  |  * Pre-conditions:
  12  |  *   1. `docker compose --profile full-stack up -d` is running.
  13  |  *   2. The dev auth account (default `admin` / `admin123`) is available.
  14  |  *   3. LibreOffice + Noto CJK are installed in the meeting-api container
  15  |  *      (the prod Dockerfile bundles them).
  16  |  *
  17  |  * The spec deliberately keeps timing wide because the meeting-api
  18  |  * callback loop is asynchronous; we wait for the page to surface a
  19  |  * stable terminal state rather than poll the API directly.
  20  |  *
  21  |  * The "full upload→SSE→RAG→PDF" scenario is gated on
  22  |  * `E2E_AUDIO_FIXTURE` pointing at a ≤30 s WAV — without it, ASR
  23  |  * runtime stays in deterministic-fake mode (no real callback) so the
  24  |  * pipeline never produces a transcript and the spec would fail
  25  |  * spuriously. CI mounts a fixture at `e2e/fixtures/sample-30s.wav`.
  26  |  */
  27  | 
  28  | const DEMO_USER = process.env.E2E_USER ?? "admin";
  29  | const DEMO_PASS = process.env.E2E_PASS ?? "admin123";
  30  | const AUDIO_FIXTURE = process.env.E2E_AUDIO_FIXTURE
  31  |   ?? resolve(__dirname_local, "..", "fixtures", "sample-30s.wav");
  32  | 
  33  | async function login(page: import("@playwright/test").Page): Promise<void> {
> 34  |   await page.goto("/login");
      |              ^ Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:5173/login
  35  |   await page.getByLabel(/账号|用户名|username/i).fill(DEMO_USER);
  36  |   await page.getByLabel(/密码|password/i).fill(DEMO_PASS);
  37  |   await page.getByRole("button", { name: /登录|sign in/i }).click();
  38  |   await expect(page).toHaveURL(/\/meetings$/);
  39  | }
  40  | 
  41  | async function createMeeting(
  42  |   page: import("@playwright/test").Page,
  43  |   title: string
  44  | ): Promise<string> {
  45  |   await page.getByRole("link", { name: /新建会议|create/i }).first().click();
  46  |   await page.getByLabel(/标题|title/i).fill(title);
  47  |   await page.getByLabel(/语言|language/i).selectOption("zh");
  48  |   await page.getByRole("button", { name: /创建|submit/i }).click();
  49  |   await expect(page).toHaveURL(/\/meetings\/m_[a-z0-9]+/);
  50  |   return page.url();
  51  | }
  52  | 
  53  | test("login → create meeting → transcript / exports pages render", async ({ page }) => {
  54  |   await login(page);
  55  |   const meetingUrl = await createMeeting(page, `E2E smoke ${Date.now()}`);
  56  | 
  57  |   // Verify transcript / exports pages render the "no content yet"
  58  |   // fallbacks; the audio upload pipeline is exercised more
  59  |   // thoroughly by export-pdf-smoke.sh, which can validate the
  60  |   // actual PDF bytes via pdftotext.
  61  |   await page.goto(meetingUrl + "/transcript");
  62  | 
  63  |   // DIAGNOSTIC: Check what's on the page
  64  |   const pageContent = await page.content();
  65  |   if (!(await page.getByRole("heading", { name: /转录|transcript/i }).isVisible())) {
  66  |     console.error(`[E2E DIAGNOSTIC] Transcript page heading not found. Page content sample:`);
  67  |     console.error(pageContent.substring(0, 1000));
  68  |     // Check if it's an error page or auth redirect
  69  |     const url = page.url();
  70  |     console.error(`  Current URL: ${url}`);
  71  |   }
  72  | 
  73  |   await expect(page.getByRole("heading", { name: /转录|transcript/i })).toBeVisible();
  74  | 
  75  |   await page.goto(meetingUrl + "/exports");
  76  |   await expect(page.getByRole("heading", { name: /导出|export/i })).toBeVisible();
  77  | });
  78  | 
  79  | test("upload → SSE → transcript → RAG → PDF export", async ({ page }) => {
  80  |   test.skip(!existsSync(AUDIO_FIXTURE),
  81  |     `audio fixture not found at ${AUDIO_FIXTURE}; set E2E_AUDIO_FIXTURE or stage e2e/fixtures/sample-30s.wav`);
  82  | 
  83  |   await login(page);
  84  |   const meetingUrl = await createMeeting(page, `E2E full ${Date.now()}`);
  85  | 
  86  |   // ── Audio upload ──────────────────────────────────────────
  87  |   await page.goto(meetingUrl + "/audio");
  88  |   const fileInput = page.getByLabel(/选择音频|audio file|upload/i).first();
  89  |   await fileInput.setInputFiles(AUDIO_FIXTURE);
  90  |   // The upload page kicks off multipart upload + auto-redirects to the
  91  |   // task progress page on completion. Wait up to 5 min for the worker
  92  |   // to finish (deterministic ASR is fast; real ASR may take longer).
  93  |   await page.waitForURL(/\/meetings\/m_[a-z0-9]+\/tasks\/task_[a-z0-9]+/, { timeout: 300_000 });
  94  | 
  95  |   // ── Task progress: wait for terminal status via the SSE-driven UI ──
  96  |   await expect(page.getByText(/SUCCEEDED|PARTIAL_SUCCEEDED|已完成/i))
  97  |     .toBeVisible({ timeout: 300_000 });
  98  | 
  99  |   // ── Transcript visible ────────────────────────────────────
  100 |   await page.goto(meetingUrl + "/transcript");
  101 |   await expect(page.getByText(/SPEAKER_/i)).toBeVisible({ timeout: 30_000 });
  102 | 
  103 |   // ── RAG: ask a question and check citation appears ────────
  104 |   await page.goto("/rag");
  105 |   await page.getByLabel(/问题|question/i).fill("会议主要讨论了什么");
  106 |   await page.getByRole("button", { name: /提问|ask|submit/i }).click();
  107 |   await expect(page.getByLabel(/rag-answer-body/i))
  108 |     .toBeVisible({ timeout: 60_000 });
  109 |   // Coverage badge always shown.
  110 |   await expect(page.getByLabel(/rag-coverage/i)).toBeVisible();
  111 | 
  112 |   // ── PDF export ────────────────────────────────────────────
  113 |   await page.goto(meetingUrl + "/exports");
  114 |   await page.getByLabel(/格式|format/i).selectOption("PDF");
  115 |   await page.getByRole("button", { name: /创建|create|export/i }).click();
  116 |   // Wait for the new row to flip to "已完成".
  117 |   await expect(page.getByText(/已完成|SUCCEEDED/i).first())
  118 |     .toBeVisible({ timeout: 180_000 });
  119 |   // Download button is enabled once SUCCEEDED + not revoked.
  120 |   await expect(page.getByRole("link", { name: /下载|download/i }).first())
  121 |     .toBeVisible();
  122 | });
  123 | 
```