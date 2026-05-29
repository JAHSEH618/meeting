import { expect, test, type Route } from "@playwright/test";

test("new meeting one-shot happy path", async ({ page }) => {
  await page.route("**/admin/documents?**", (route) => json(route, []));
  await page.route("**/admin/documents", async (route) => {
    if (route.request().method() === "POST") {
      return json(route, { documentId: "doc-new", title: "ref.pdf", securityLevel: "INTERNAL" });
    }
    return json(route, []);
  });
  await page.route("**/admin/files/uploads", (route) =>
    json(route, { uploadId: "doc-up", parts: [{ partNumber: 1, uploadUrl: "https://presign.test/doc/1", expiresAt: "", headers: {} }] }),
  );
  await page.route("**/admin/files/uploads/doc-up/complete", (route) =>
    json(route, { fileId: "file-doc", sha256: "a".repeat(64), sizeBytes: 1024, contentType: "application/pdf" }),
  );
  await page.route("**/admin/files/uploads/doc-up/abort", (route) => json(route, null));
  await page.route("https://presign.test/**", (route) =>
    route.fulfill({ status: 200, headers: { etag: '"etag-1"' }, body: "" }),
  );

  await page.route("**/admin/meetings", async (route) => {
    if (route.request().method() === "POST") {
      return json(route, {
        meetingId: "m-new",
        title: "季度评审",
        status: "RUNNING",
        securityLevel: "INTERNAL",
        language: "zh",
        createdAt: "2026-05-27T00:00:00Z",
      });
    }
    return json(route, []);
  });
  await page.route("**/admin/meetings/m-new/glossary", (route) => json(route, { meetingId: "m-new", terms: [{ term: "LLM", aliases: [] }] }));
  await page.route("**/admin/meetings/m-new/documents:attach", (route) =>
    json(route, { id: "link1", documentId: "doc-new", title: "ref.pdf", role: "REFERENCE", securityLevel: "INTERNAL", attachedBy: null, attachedAt: "" }),
  );
  await page.route("**/admin/meetings/m-new/files/audio/uploads", (route) =>
    json(route, { uploadId: "audio-up", parts: [{ partNumber: 1, uploadUrl: "https://presign.test/audio/1", expiresAt: "", headers: {} }] }),
  );
  await page.route("**/admin/meetings/m-new/files/audio/uploads/audio-up/complete", (route) =>
    json(route, { uploadId: "audio-up", uploadStatus: "COMPLETED", fileId: "audio-file", parts: [] }),
  );
  await page.route("**/admin/meetings/m-new/files/audio/uploads/audio-up/abort", (route) => json(route, null));
  await page.route("**/admin/meetings/m-new/exports", (route) =>
    json(route, { exportId: "exp1", status: "RUNNING", format: "DOCX" }),
  );
  await page.route("**/admin/meetings/m-new/exports/exp1", (route) =>
    json(route, { exportId: "exp1", status: "SUCCEEDED", format: "DOCX", downloadUrl: "https://download.test/minutes.docx" }),
  );
  await page.route("**/admin/meetings/m-new", (route) =>
    json(route, {
      meeting: { success: true, data: { meetingId: "m-new", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task-1", meetingId: "m-new", status: "SUCCEEDED", phase: "TERMINAL", attemptNo: 1, currentStep: "EXTRACTION", lastErrorCode: null, retryable: false, steps: [
        { stepName: "ASR", status: "SUCCEEDED", progress: 100 },
        { stepName: "SUMMARY", status: "SUCCEEDED", progress: 100 },
      ] } },
      speakers: { success: true, data: [{ label: "SPEAKER_01", displayName: "李四", verificationStatus: "CONFIRMED", candidates: [] }] },
      minutes: { success: true, data: { title: "纪要", markdown: "# 会议纪要\n\n本测试纪要内容。", minutesVersion: 1 } },
    }),
  );
  await page.route("**/api/processing-tasks/task-1/events", (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: "event: TASK_SNAPSHOT\ndata: {\"taskId\":\"task-1\",\"status\":\"SUCCEEDED\",\"steps\":[{\"stepName\":\"ASR\",\"status\":\"SUCCEEDED\",\"progress\":100},{\"stepName\":\"SUMMARY\",\"status\":\"SUCCEEDED\",\"progress\":100}]}\n\n",
    }),
  );

  await page.goto("/workstation/meetings/new?playwright-skip-auth=1");
  await page.getByLabel("标题").fill("季度评审");
  const termInput = page.getByPlaceholder(/按 Enter 添加术语/);
  await termInput.fill("LLM");
  await termInput.press("Enter");
  await expect(page.getByText("LLM")).toBeVisible();

  await page.setInputFiles("#reference-document-upload", {
    name: "ref.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from(new Uint8Array(1024)),
  });
  await expect(page.getByText("已上传")).toBeVisible();

  await page.setInputFiles("#meeting-audio-file", {
    name: "demo.mp3",
    mimeType: "audio/mpeg",
    buffer: Buffer.from(new Uint8Array(1024)),
  });
  await page.getByTestId("start-processing").click();
  await expect(page).toHaveURL(/\/workstation\/meetings\/m-new$/);
  await expect(page.getByTestId("step-ASR")).toContainText("SUCCEEDED");
  await expect(page.getByText(/李四/)).toBeVisible();
  await expect(page.getByText(/本测试纪要内容/)).toBeVisible();

  await page.getByTestId("export-docx").click();
  await expect(page.getByTestId("download-link")).toHaveAttribute("href", "https://download.test/minutes.docx");
});

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify({ success: status < 400, data, error: null, requestId: "r", traceId: "t" }),
  });
}
