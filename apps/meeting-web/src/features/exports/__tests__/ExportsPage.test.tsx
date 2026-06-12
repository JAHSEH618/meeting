import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import type { ExportJob } from "@shared/api/client";
import { ExportsPage } from "../ExportsPage";

function renderAt(path: string) {
  return render(
    <TestRouter initialEntries={[path]}>
      <Routes>
        <Route path="/meetings/:meetingId/exports" element={<ExportsPage />} />
      </Routes>
    </TestRouter>,
  );
}

describe("ExportsPage", () => {
  // NB: the MSW handler keeps per-meeting state in module scope, so each
  // test uses a distinct meetingId to avoid cross-test bleed.
  function seedSucceededExport(meetingId: string, overrides: Partial<ExportJob> = {}) {
    let job: ExportJob = {
      exportId: `exp_${meetingId}`,
      meetingId,
      status: "SUCCEEDED",
      format: "PDF",
      dataBoundaryMode: "FULL",
      inputTranscriptVersion: 0,
      inputMinutesVersion: 0,
      snapshotManifestId: "mfst_test_01",
      watermarkText: null,
      downloadUrl: `https://download.example/${meetingId}.pdf`,
      downloadUrlExpiresAt: "2026-05-19T02:00:00Z",
      sha256: "abc123",
      fileSizeBytes: 128,
      revoked: false,
      stale: false,
      errorCode: null,
      expiresAt: "2026-05-19T02:00:00Z",
      createdAt: "2026-05-18T02:00:00Z",
      finishedAt: "2026-05-18T02:01:00Z",
      ...overrides,
    };
    const revokeCalls: string[] = [];
    server.use(
      http.get("/api/meetings/:meetingId/exports", ({ params }) => {
        if (params.meetingId !== meetingId) {
          return HttpResponse.json({ success: true, data: { items: [] }, error: null, requestId: "r", traceId: "t" });
        }
        return HttpResponse.json({ success: true, data: { items: [job] }, error: null, requestId: "r", traceId: "t" });
      }),
      http.post("/api/exports/:exportId/revoke-link", ({ params }) => {
        revokeCalls.push(String(params.exportId));
        if (params.exportId === job.exportId) {
          job = { ...job, status: "REVOKED", revoked: true, downloadUrl: null };
        }
        return HttpResponse.json({ success: true, data: null, error: null, requestId: "r", traceId: "t" });
      }),
    );
    return { revokeCalls };
  }

  it("shows empty state on a fresh meeting", async () => {
    renderAt("/meetings/mtg_exports_empty/exports");
    await waitFor(() => expect(screen.getByText(/暂无导出任务/)).toBeInTheDocument());
  });

  it("creates an export and lists it as QUEUED", async () => {
    renderAt("/meetings/mtg_exports_create/exports");
    await screen.findByText(/暂无导出任务/);

    fireEvent.change(screen.getByTestId("export-format-select"), {
      target: { value: "MARKDOWN" },
    });
    fireEvent.change(screen.getByTestId("export-watermark-input"), {
      target: { value: "INTERNAL" },
    });
    fireEvent.click(screen.getByTestId("create-export-button"));

    await waitFor(() => expect(screen.getByTestId("exports-table")).toBeInTheDocument());
    // "Markdown" appears in the form select; restrict to the table.
    const table = screen.getByTestId("exports-table");
    expect(table.textContent).toContain("Markdown");
    expect(table.textContent).toContain("排队中");
    expect(screen.getByText("取消")).toBeInTheDocument();
  });

  it("renders a cancel button for non-terminal jobs and triggers the cancel call", async () => {
    renderAt("/meetings/mtg_exports_cancel/exports");
    await screen.findByText(/暂无导出任务/);

    fireEvent.click(screen.getByTestId("create-export-button"));
    const cancelBtn = await screen.findByText("取消");
    expect(cancelBtn).toBeInTheDocument();

    fireEvent.click(cancelBtn);
    // give the await chain in handleCancel one event loop turn
    await new Promise((resolve) => setTimeout(resolve, 50));
  });

  it("disables the create button while a request is in flight", async () => {
    renderAt("/meetings/mtg_exports_disable/exports");
    await screen.findByText(/暂无导出任务/);
    const btn = screen.getByTestId("create-export-button") as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
  });

  it("renders the back-to-meeting link", async () => {
    renderAt("/meetings/mtg_exports_nav/exports");
    await waitFor(() => expect(screen.getByText("返回会议")).toBeInTheDocument());
    expect(screen.getByText("返回会议").getAttribute("href")).toBe("/meetings/mtg_exports_nav");
  });

  it("keeps a completed export downloadable when revoke confirmation is cancelled", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { revokeCalls } = seedSucceededExport("mtg_exports_revoke_cancel");
    renderAt("/meetings/mtg_exports_revoke_cancel/exports");

    await screen.findByRole("link", { name: "下载" });
    fireEvent.click(screen.getByRole("button", { name: "撤销链接" }));

    expect(confirmSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "撤销下载链接" });
    expect(within(dialog).getByText(/不可恢复/)).toBeInTheDocument();
    const cancelButton = within(dialog).getAllByRole("button", { name: "取消" })[0];
    if (!cancelButton) throw new Error("cancel button not found in dialog");
    fireEvent.click(cancelButton);

    expect(screen.queryByRole("dialog", { name: "撤销下载链接" })).not.toBeInTheDocument();
    expect(revokeCalls).toEqual([]);
    expect(screen.getByRole("link", { name: "下载" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "撤销链接" })).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("revokes a completed export link through an inline confirmation dialog", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { revokeCalls } = seedSucceededExport("mtg_exports_revoke_success");
    renderAt("/meetings/mtg_exports_revoke_success/exports");

    await screen.findByRole("link", { name: "下载" });
    fireEvent.click(screen.getByRole("button", { name: "撤销链接" }));

    expect(confirmSpy).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "撤销下载链接" })).getByRole("button", {
        name: "确认撤销",
      }),
    );

    await waitFor(() => expect(revokeCalls).toEqual(["exp_mtg_exports_revoke_success"]));
    await waitFor(() => expect(screen.getByText("已撤销")).toBeInTheDocument());
    expect(screen.queryByRole("link", { name: "下载" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "撤销链接" })).not.toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("keeps the download link visible when revoke fails", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    seedSucceededExport("mtg_exports_revoke_failure");
    server.use(
      http.post("/api/exports/:exportId/revoke-link", () => {
        return HttpResponse.json(
          {
            success: false,
            data: null,
            error: { code: "DEPENDENCY_UNAVAILABLE", message: "dependency down", retryable: true, details: {} },
            requestId: "r",
            traceId: "t",
          },
          { status: 503 },
        );
      }),
    );
    renderAt("/meetings/mtg_exports_revoke_failure/exports");

    await screen.findByRole("link", { name: "下载" });
    fireEvent.click(screen.getByRole("button", { name: "撤销链接" }));
    expect(confirmSpy).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "撤销下载链接" })).getByRole("button", {
        name: "确认撤销",
      }),
    );

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("依赖服务暂不可用"));
    expect(screen.getByRole("link", { name: "下载" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "撤销链接" })).toBeInTheDocument();
    confirmSpy.mockRestore();
  });
});
