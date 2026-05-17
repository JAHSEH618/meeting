import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { DeletionJobsPage } from "../DeletionJobsPage";

describe("DeletionJobsPage", () => {
  it("shows empty state on first load", async () => {
    render(
      <MemoryRouter>
        <DeletionJobsPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText(/暂无删除任务/)).toBeInTheDocument());
  });

  it("opens the create form and validates required fields", async () => {
    render(
      <MemoryRouter>
        <DeletionJobsPage />
      </MemoryRouter>,
    );
    await screen.findByText(/暂无删除任务|删除任务列表/);
    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    expect(screen.getByTestId("dj-create-form")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("dj-create-submit"));
    await waitFor(() =>
      expect(screen.getByTestId("dj-create-error")).toHaveTextContent("请填写"),
    );
  });

  it("creates a deletion job after confirm()", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <MemoryRouter>
        <DeletionJobsPage />
      </MemoryRouter>,
    );
    await screen.findByText(/暂无删除任务|删除任务列表/);

    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    fireEvent.change(screen.getByTestId("dj-scope-id"), { target: { value: "mtg_dj_create_01" } });
    fireEvent.change(screen.getByTestId("dj-reason"), { target: { value: "GDPR Art. 17" } });
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    await waitFor(() => expect(screen.getByTestId("dj-table")).toBeInTheDocument());
    expect(screen.getByText("mtg_dj_create_01")).toBeInTheDocument();
    expect(screen.getByText("排队中")).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("aborts creation when confirm() is rejected", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <MemoryRouter>
        <DeletionJobsPage />
      </MemoryRouter>,
    );
    await screen.findByText(/暂无删除任务|删除任务列表/);

    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    fireEvent.change(screen.getByTestId("dj-scope-id"), { target: { value: "mtg_dj_abort_01" } });
    fireEvent.change(screen.getByTestId("dj-reason"), { target: { value: "regulator" } });
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    expect(confirmSpy).toHaveBeenCalledOnce();
    // The exact row should not appear (other tests may have created
    // unrelated rows in the shared module-scope handler state).
    expect(screen.queryByText("mtg_dj_abort_01")).not.toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("displays BLOCKED_BY_LEGAL_HOLD for scopes containing _protected", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <MemoryRouter>
        <DeletionJobsPage />
      </MemoryRouter>,
    );
    await screen.findByText(/暂无删除任务|删除任务列表/);

    fireEvent.click(screen.getByTestId("toggle-create-deletion-job"));
    fireEvent.change(screen.getByTestId("dj-scope-id"), {
      target: { value: "mtg_dj_protected_01" },
    });
    fireEvent.change(screen.getByTestId("dj-reason"), { target: { value: "test" } });
    fireEvent.click(screen.getByTestId("dj-create-submit"));

    await waitFor(() => expect(screen.getByText("被法定保全阻断")).toBeInTheDocument());
    // The user-facing error mapper is exercised by listLegalHolds /
    // listDeletionJobs read-path; only assert the BLOCKED status here.
    confirmSpy.mockRestore();
  });
});
