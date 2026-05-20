import { test, expect, type Route } from "@playwright/test";

/**
 * Workstation happy-path: build meeting → upload (skip) → glossary → start →
 * speakers (refresh) → finalize → create export → download docx.
 *
 * All /admin/* calls are intercepted client-side so the test does not need
 * an upstream ai-worker process. The page.route handlers are the canonical
 * stubs — keep them in sync with the BFF endpoint surface (src/shared/api/endpoints.ts).
 */

const ADMIN = "**/admin/**";

test("workstation wizard end-to-end", async ({ page }) => {
  const calls: string[] = [];

  await page.route(ADMIN, async (route: Route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    calls.push(`${method} ${path}`);

    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: body, error: null, requestId: "r", traceId: "t" }),
      });

    if (method === "POST" && path === "/admin/meetings") {
      return json({
        meetingId: "m_e2e",
        title: "E2E Meeting",
        status: "CREATED",
        securityLevel: "INTERNAL",
        language: "zh",
        createdAt: "2026-05-19T05:00:00Z",
      });
    }
    if (method === "PATCH" && /\/admin\/meetings\/.*\/glossary$/.test(path)) {
      return json({ meetingId: "m_e2e", terms: [{ term: "KPI" }] });
    }
    if (method === "GET" && /\/admin\/documents/.test(path)) {
      return json([]);
    }
    if (method === "POST" && /\/admin\/meetings\/.*:start-processing$/.test(path)) {
      return json({ taskId: "t_e2e", phase: "WORKER_DAG_RUNNING", status: "RUNNING", attemptNo: 1 });
    }
    if (method === "GET" && /\/admin\/meetings\/[^/]+$/.test(path)) {
      return json({
        meeting: { success: true, data: { meetingId: "m_e2e", title: "E2E", status: "CREATED", securityLevel: "INTERNAL", language: "zh", createdAt: "" } },
        latestTask: null,
        speakers: { success: true, data: [] },
        minutes: null,
      });
    }
    if (method === "POST" && /\/admin\/meetings\/.*:finalize$/.test(path)) {
      return json({ taskId: "t_e2e", phase: "JAVA_LLM_RUNNING", status: "RUNNING", attemptNo: 1 });
    }
    if (method === "POST" && /\/admin\/meetings\/.*\/exports$/.test(path)) {
      return json({ exportId: "exp_e2e", status: "RUNNING", format: "DOCX" });
    }
    if (method === "GET" && /\/admin\/meetings\/.*\/exports\/.*$/.test(path)) {
      return json({
        exportId: "exp_e2e",
        status: "SUCCEEDED",
        format: "DOCX",
        downloadUrl: "https://tos.example.com/exp_e2e.docx?sig=abc",
        downloadExpiresAt: "2027-01-01T00:00:00Z",
      });
    }
    return json(null);
  });

  // Skip the auth bounce. The SPA is mounted under /workstation/ in both
  // dev (vite base) and prod (FastAPI StaticFiles), so the test must use
  // the prefixed path — bare /meetings/new would land outside BrowserRouter
  // basename and render an empty page.
  await page.goto("/workstation/meetings/new?playwright-skip-auth=1");

  // STEP 1 — meeting metadata
  await page.getByLabel("meeting title").fill("E2E Meeting");
  await page.getByRole("button", { name: /下一步：上传录音/ }).click();

  // STEP 2 — audio (skip to glossary)
  await page.getByRole("button", { name: /跳到术语/ }).click();

  // STEP 3a — glossary
  const termInput = page.getByLabel("term draft");
  await termInput.fill("KPI");
  await termInput.press("Enter");
  await expect(page.getByText("KPI").first()).toBeVisible();
  await page.getByRole("button", { name: /保存并下一步/ }).click();

  // STEP 3b — documents (no attach in happy-path)
  await page.getByRole("button", { name: /下一步：开始处理/ }).click();

  // STEP 4 — start processing
  await page.getByTestId("start-processing").click();
  await expect(page.getByTestId("start-processing")).toHaveText(/已开始/);
  await page.getByRole("button", { name: /下一步：认人/ }).click();

  // STEP 5 — speakers (just move on after refresh)
  await page.getByRole("button", { name: /跳到生成纪要/ }).click();

  // STEP 6a — finalize
  await page.getByTestId("finalize").click();
  await expect(page.getByTestId("finalize")).toHaveText(/已 finalize/);
  await page.getByRole("button", { name: /下一步：下载/ }).click();

  // STEP 6c — export + download
  await page.getByTestId("create-export").click();
  await expect(page.getByTestId("download-link")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId("download-link")).toHaveAttribute("href", /\.docx/);

  // Sanity — the orchestration sequence we recorded should include the
  // critical D3 finalize call (resume-java-phase happens server-side; here we
  // verify the BFF endpoint surface was exercised in order).
  expect(calls.filter((c) => c.includes("start-processing"))).toHaveLength(1);
  expect(calls.filter((c) => c.includes(":finalize"))).toHaveLength(1);
  expect(calls.filter((c) => c.includes("/exports"))).toHaveLength(2); // POST + GET
});
