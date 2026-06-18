import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { LegalHoldsPage } from "../LegalHoldsPage";

describe("LegalHoldsPage", () => {
  it("shows empty state when there are no holds", async () => {
    const { container } = render(
      <TestRouter>
        <LegalHoldsPage />
      </TestRouter>,
    );
    expect(container.querySelector(".page--dense")).toBeInTheDocument();
    expect(container.querySelector(".page-hero--compact")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/暂无法定保全/)).toBeInTheDocument());
  });

  it("opens the create form and validates required fields", async () => {
    render(
      <TestRouter>
        <LegalHoldsPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无法定保全|保全列表/);
    fireEvent.click(screen.getByTestId("toggle-create-legal-hold"));
    expect(screen.getByTestId("lh-create-form")).toBeInTheDocument();

    // Click submit with blank fields -> validation error
    fireEvent.click(screen.getByTestId("lh-create-submit"));
    await waitFor(() =>
      expect(screen.getByTestId("lh-create-error")).toHaveTextContent("请填写"),
    );
  });

  it("creates a legal hold and lists it as active", async () => {
    render(
      <TestRouter>
        <LegalHoldsPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无法定保全|保全列表/);

    fireEvent.click(screen.getByTestId("toggle-create-legal-hold"));
    fireEvent.change(screen.getByTestId("lh-scope-id"), { target: { value: "mtg_test_01" } });
    fireEvent.change(screen.getByTestId("lh-reason"), { target: { value: "监管调查" } });
    fireEvent.click(screen.getByTestId("lh-create-submit"));

    await waitFor(() => expect(screen.getByTestId("lh-table")).toBeInTheDocument());
    expect(screen.getAllByText("目标已记录").length).toBeGreaterThan(0);
    expect(screen.getByText("监管调查")).toBeInTheDocument();
    expect(screen.getByText("生效中")).toBeInTheDocument();
  });

  it("releases a legal hold through an inline confirmation dialog", async () => {
    const promptSpy = vi.spyOn(window, "prompt");
    render(
      <TestRouter>
        <LegalHoldsPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无法定保全|保全列表/);

    fireEvent.click(screen.getByTestId("toggle-create-legal-hold"));
    fireEvent.change(screen.getByTestId("lh-scope-id"), { target: { value: "mtg_release_01" } });
    fireEvent.change(screen.getByTestId("lh-reason"), { target: { value: "诉讼保全" } });
    fireEvent.click(screen.getByTestId("lh-create-submit"));

    const reasonCell = await screen.findByText("诉讼保全");
    const row = reasonCell.closest("tr");
    expect(row).not.toBeNull();
    fireEvent.click(within(row as HTMLTableRowElement).getByRole("button", { name: "释放" }));

    expect(promptSpy).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "释放法定保全" })).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("lh-release-submit"));
    expect(screen.getByTestId("lh-release-error")).toHaveTextContent("请填写释放原因");

    fireEvent.change(screen.getByTestId("lh-release-reason"), { target: { value: "case closed" } });
    fireEvent.click(screen.getByTestId("lh-release-submit"));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "释放法定保全" })).not.toBeInTheDocument());
    expect(screen.getByText("已释放（case closed）")).toBeInTheDocument();
    promptSpy.mockRestore();
  });
});
