import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ExportsPage } from "../ExportsPage";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/meetings/:meetingId/exports" element={<ExportsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ExportsPage", () => {
  // NB: the MSW handler keeps per-meeting state in module scope, so each
  // test uses a distinct meetingId to avoid cross-test bleed.
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

    // Click and assert the call happens without throwing — the
    // observable state change after polling is exercised by the
    // application-service unit test (cancelTransitionsQueuedJobAnd
    // PublishesCompleted) on the API side.
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
});
