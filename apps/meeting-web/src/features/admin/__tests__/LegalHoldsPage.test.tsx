import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { LegalHoldsPage } from "../LegalHoldsPage";

describe("LegalHoldsPage", () => {
  it("shows empty state when there are no holds", async () => {
    render(
      <TestRouter>
        <LegalHoldsPage />
      </TestRouter>,
    );
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

  it("creates a legal hold and lists it as ACTIVE", async () => {
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
    expect(screen.getByText("mtg_test_01")).toBeInTheDocument();
    expect(screen.getByText("监管调查")).toBeInTheDocument();
    expect(screen.getByText("生效中")).toBeInTheDocument();
  });
});
