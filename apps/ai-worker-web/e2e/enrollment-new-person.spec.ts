import { expect, test, type Route } from "@playwright/test";

test("enrollment new person happy path", async ({ page }) => {
  await page.route("**/admin/persons**", async (route: Route) => {
    const method = route.request().method();
    if (method === "GET") return json(route, []);
    if (method === "POST") {
      return json(route, {
        personId: "p-new",
        displayName: "李四",
        email: null,
        externalId: null,
        createdAt: "2026-05-27T00:00:00Z",
      });
    }
    return json(route, null);
  });

  await page.route("**/admin/enrollment/sessions", (route) =>
    json(route, { sessionId: "s1", state: "CREATED", personId: "p-new" }),
  );
  await page.route("**/admin/enrollment/sessions/s1/audio", (route) => json(route, null));
  await page.route("**/admin/enrollment/sessions/s1/preview", (route) =>
    json(route, { sessionId: "s1", state: "PREVIEWED", personId: "p-new", qualityScore: 0.82 }),
  );
  await page.route("**/admin/enrollment/sessions/s1/commit", (route) =>
    json(route, { sessionId: "s1", state: "COMMITTED", personId: "p-new", qualityScore: 0.82 }),
  );

  await page.goto("/workstation/enrollment?playwright-skip-auth=1");
  await page.getByPlaceholder(/按姓名/).fill("李四");
  await page.getByRole("button", { name: /新建人员/ }).click();
  await page.getByLabel("姓名").fill("李四");
  await page.getByRole("button", { name: /^创建$/ }).click();

  await expect(page.getByText(/已选择：李四/)).toBeVisible();
  await page.getByRole("button", { name: /创建录入会话/ }).click();
  await expect(page.getByTestId("session-id")).toContainText("s1");

  await page.setInputFiles("#enrollment-audio-file", {
    name: "voice.wav",
    mimeType: "audio/wav",
    buffer: Buffer.from(new Uint8Array(1024)),
  });
  await page.getByRole("button", { name: /上传并预览/ }).click();
  await expect(page.getByTestId("quality-score")).toContainText("0.82");
  await page.getByRole("button", { name: /确认录入/ }).click();
  await expect(page.getByText(/状态: COMMITTED/)).toBeVisible();
});

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify({ success: status < 400, data, error: null, requestId: "r", traceId: "t" }),
  });
}
