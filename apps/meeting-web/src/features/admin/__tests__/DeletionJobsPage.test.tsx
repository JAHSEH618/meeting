import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { DeletionJobsPage } from "../DeletionJobsPage";

describe("DeletionJobsPage", () => {
  async function openFilledCreateForm(scopeId: string, reason = "GDPR Art. 17") {
    await screen.findByText(/暂无删除任务|删除任务列表/);
    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    fireEvent.change(screen.getByTestId("dj-scope-id"), { target: { value: scopeId } });
    fireEvent.change(screen.getByTestId("dj-reason"), { target: { value: reason } });
  }

  it("shows empty state on first load", async () => {
    render(
      <TestRouter>
        <DeletionJobsPage />
      </TestRouter>,
    );
    await waitFor(() => expect(screen.getByText(/暂无删除任务/)).toBeInTheDocument());
  });

  it("opens the create form and validates required fields", async () => {
    render(
      <TestRouter>
        <DeletionJobsPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无删除任务|删除任务列表/);
    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    expect(screen.getByTestId("dj-create-form")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("dj-create-submit"));
    await waitFor(() =>
      expect(screen.getByTestId("dj-create-error")).toHaveTextContent("请填写"),
    );
  });

  it("keeps the create form unchanged when inline confirmation is cancelled", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    const createCalls: unknown[] = [];
    server.use(
      http.post("/api/admin/deletion-jobs", async ({ request }) => {
        createCalls.push(await request.json());
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
        <DeletionJobsPage />
      </TestRouter>,
    );

    await openFilledCreateForm("mtg_dj_abort_01", "regulator");
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    expect(confirmSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "确认删除任务" });
    expect(within(dialog).getByText(/mtg_dj_abort_01/)).toBeInTheDocument();
    expect(within(dialog).getByText(/regulator/)).toBeInTheDocument();
    const cancelButton = within(dialog).getAllByRole("button", { name: "取消" })[0];
    if (!cancelButton) throw new Error("cancel button not found in dialog");
    fireEvent.click(cancelButton);

    expect(screen.queryByRole("dialog", { name: "确认删除任务" })).not.toBeInTheDocument();
    expect(createCalls).toEqual([]);
    expect(screen.getByTestId("dj-create-form")).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("creates a deletion job through an inline confirmation dialog", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <TestRouter>
        <DeletionJobsPage />
      </TestRouter>,
    );

    await openFilledCreateForm("mtg_dj_create_01");
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    expect(confirmSpy).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "确认删除任务" })).getByRole("button", {
        name: "确认提交",
      }),
    );
    await waitFor(() => expect(screen.getByTestId("dj-table")).toBeInTheDocument());
    expect(screen.getByText("mtg_dj_create_01")).toBeInTheDocument();
    expect(screen.getByText("排队中")).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("requires the tenant deletion passphrase before creating a tenant deletion job", async () => {
    const promptSpy = vi.spyOn(window, "prompt").mockReturnValue("WRONG");
    const createCalls: unknown[] = [];
    server.use(
      http.post("/api/admin/deletion-jobs", async ({ request }) => {
        createCalls.push(await request.json());
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
        <DeletionJobsPage />
      </TestRouter>,
    );

    await openFilledCreateForm("tenant_critical_01", "offboarding");
    fireEvent.change(screen.getByTestId("dj-scope-type"), { target: { value: "TENANT" } });
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    expect(promptSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "确认删除任务" });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认提交" }));

    expect(screen.getByRole("alert")).toHaveTextContent("请输入 DELETE-TENANT");
    expect(createCalls).toEqual([]);
    promptSpy.mockRestore();
  });

  it("creates a tenant deletion job after the DELETE-TENANT passphrase is entered", async () => {
    const promptSpy = vi.spyOn(window, "prompt").mockReturnValue(null);
    render(
      <TestRouter>
        <DeletionJobsPage />
      </TestRouter>,
    );

    await openFilledCreateForm("tenant_dj_create_01", "tenant teardown");
    fireEvent.change(screen.getByTestId("dj-scope-type"), { target: { value: "TENANT" } });
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    expect(promptSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "确认删除任务" });
    fireEvent.change(within(dialog).getByLabelText("确认口令"), {
      target: { value: "DELETE-TENANT" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认提交" }));

    await waitFor(() => expect(screen.getByText("tenant_dj_create_01")).toBeInTheDocument());
    expect(screen.getByText("整租户（高危）")).toBeInTheDocument();
    promptSpy.mockRestore();
  });

  it("displays BLOCKED_BY_LEGAL_HOLD for scopes containing _protected", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <TestRouter>
        <DeletionJobsPage />
      </TestRouter>,
    );

    await openFilledCreateForm("mtg_dj_protected_01", "test");
    fireEvent.click(screen.getByTestId("dj-create-submit"));
    expect(confirmSpy).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "确认删除任务" })).getByRole("button", {
        name: "确认提交",
      }),
    );

    await waitFor(() => expect(screen.getByText("被法定保全阻断")).toBeInTheDocument());
    // The user-facing error mapper is exercised by listLegalHolds /
    // listDeletionJobs read-path; only assert the BLOCKED status here.
    confirmSpy.mockRestore();
  });
});
