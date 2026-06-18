import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { BreakGlassPage } from "../BreakGlassPage";

describe("BreakGlassPage", () => {
  async function createPendingRequest(scopeId: string) {
    await screen.findByText(/暂无紧急访问申请|申请列表/);
    fireEvent.click(screen.getByTestId("toggle-create-bg"));
    fireEvent.change(screen.getByTestId("bg-scope-id"), { target: { value: scopeId } });
    fireEvent.change(screen.getByTestId("bg-reason"), { target: { value: "incident response" } });
    fireEvent.click(screen.getByTestId("bg-create-submit"));

    const table = await screen.findByTestId("bg-table");
    await waitFor(() => expect(within(table).getAllByText("待审批").length).toBeGreaterThan(0));
    const approveButton = within(table).getAllByRole("button", { name: "批准" })[0];
    if (!approveButton) throw new Error(`pending request row not found for ${scopeId}`);
    const row = approveButton.closest("tr");
    expect(row).not.toBeNull();
    return row as HTMLTableRowElement;
  }

  it("shows empty state initially", async () => {
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );
    await waitFor(() => expect(screen.getByText(/暂无紧急访问申请/)).toBeInTheDocument());
  });

  it("creates a request and lists it as PENDING", async () => {
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无紧急访问申请|申请列表/);

    fireEvent.click(screen.getByTestId("toggle-create-bg"));
    fireEvent.change(screen.getByTestId("bg-scope-id"), { target: { value: "mtg_bg_create" } });
    fireEvent.change(screen.getByTestId("bg-reason"), { target: { value: "incident response" } });
    fireEvent.click(screen.getByTestId("bg-create-submit"));

    await waitFor(() => expect(screen.getByTestId("bg-table")).toBeInTheDocument());
    expect(screen.getAllByText("目标已记录").length).toBeGreaterThan(0);
    expect(screen.getByText("待审批")).toBeInTheDocument();
    expect(screen.getAllByText(/批准|拒绝/).length).toBeGreaterThan(0);
  });

  it("validates required fields", async () => {
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无紧急访问申请|申请列表/);
    fireEvent.click(screen.getByTestId("toggle-create-bg"));
    fireEvent.click(screen.getByTestId("bg-create-submit"));
    await waitFor(() =>
      expect(screen.getByTestId("bg-create-error")).toHaveTextContent("请填写"),
    );
  });

  it("keeps a pending request unchanged when inline approval is cancelled", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockImplementation(() => {
      throw new Error("native confirm must not be used");
    });
    const approveCalls: string[] = [];
    server.use(
      http.post("/api/admin/break-glass/requests/:requestId/approve", ({ params }) => {
        approveCalls.push(String(params.requestId));
        return HttpResponse.json({
          success: true,
          data: null,
          error: null,
          requestId: "r",
          traceId: "t",
        });
      }),
    );

    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );

    const row = await createPendingRequest("mtg_bg_approve_cancel");
    fireEvent.click(within(row).getByRole("button", { name: "批准" }));

    expect(confirmSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "批准紧急访问" });
    expect(within(dialog).getByText(/目标编号已记录/)).toBeInTheDocument();
    const cancelButton = within(dialog).getAllByRole("button", { name: "取消" })[0];
    if (!cancelButton) throw new Error("cancel button not found in dialog");
    fireEvent.click(cancelButton);

    expect(screen.queryByRole("dialog", { name: "批准紧急访问" })).not.toBeInTheDocument();
    expect(approveCalls).toEqual([]);
    expect(within(row).getByText("待审批")).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("approves a pending request through an inline confirmation dialog", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockImplementation(() => {
      throw new Error("native confirm must not be used");
    });
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );

    const row = await createPendingRequest("mtg_bg_approve");
    fireEvent.click(within(row).getByRole("button", { name: "批准" }));

    expect(confirmSpy).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "批准紧急访问" })).getByRole("button", {
        name: "确认批准",
      }),
    );
    await waitFor(() => expect(screen.getByText("已批准")).toBeInTheDocument());
    confirmSpy.mockRestore();
  });

  it("requires a reason before rejecting a pending request", async () => {
    const promptSpy = vi.spyOn(window, "prompt").mockImplementation(() => {
      throw new Error("native prompt must not be used");
    });
    const rejectCalls: string[] = [];
    server.use(
      http.post("/api/admin/break-glass/requests/:requestId/reject", async ({ params }) => {
        rejectCalls.push(String(params.requestId));
        return HttpResponse.json({
          success: true,
          data: null,
          error: null,
          requestId: "r",
          traceId: "t",
        });
      }),
    );

    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );

    const row = await createPendingRequest("mtg_bg_reject_empty");
    fireEvent.click(within(row).getByRole("button", { name: "拒绝" }));

    expect(promptSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "拒绝紧急访问" });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认拒绝" }));

    expect(screen.getByRole("alert")).toHaveTextContent("请填写拒绝原因");
    expect(rejectCalls).toEqual([]);
    expect(within(row).getByText("待审批")).toBeInTheDocument();
    promptSpy.mockRestore();
  });

  it("rejects a pending request through an inline reason dialog", async () => {
    const promptSpy = vi.spyOn(window, "prompt").mockImplementation(() => {
      throw new Error("native prompt must not be used");
    });
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );

    const row = await createPendingRequest("mtg_bg_reject");
    fireEvent.click(within(row).getByRole("button", { name: "拒绝" }));

    expect(promptSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "拒绝紧急访问" });
    fireEvent.change(within(dialog).getByLabelText("拒绝原因"), {
      target: { value: "no justification" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认拒绝" }));
    await waitFor(() => expect(screen.getByText("已拒绝")).toBeInTheDocument());
    expect(screen.getByText("no justification")).toBeInTheDocument();
    promptSpy.mockRestore();
  });
});
