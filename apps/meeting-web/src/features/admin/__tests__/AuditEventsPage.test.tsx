import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { AuditEventsPage } from "../AuditEventsPage";

describe("AuditEventsPage", () => {
  it("renders the seed audit events on first load", async () => {
    render(
      <TestRouter>
        <AuditEventsPage />
      </TestRouter>,
    );
    const table = await screen.findByTestId("audit-table");
    const tableBody = within(table);
    expect(tableBody.getByText("LEGAL_HOLD_PLACE")).toBeInTheDocument();
    expect(tableBody.getByText("DELETION_REQUEST")).toBeInTheDocument();
    expect(tableBody.getByText("lh_mock_01")).toBeInTheDocument();
  });

  it("filters by action via the dropdown", async () => {
    render(
      <TestRouter>
        <AuditEventsPage />
      </TestRouter>,
    );
    await screen.findByTestId("audit-table");

    fireEvent.change(screen.getByTestId("audit-action-select"), {
      target: { value: "LEGAL_HOLD_PLACE" },
    });
    fireEvent.click(screen.getByTestId("audit-filter-submit"));

    await waitFor(() => {
      const table = screen.getByTestId("audit-table");
      const body = within(table);
      expect(body.getByText("LEGAL_HOLD_PLACE")).toBeInTheDocument();
      expect(body.queryByText("DELETION_REQUEST")).not.toBeInTheDocument();
    });
  });

  it("filters by actor user id", async () => {
    render(
      <TestRouter>
        <AuditEventsPage />
      </TestRouter>,
    );
    await screen.findByTestId("audit-table");

    fireEvent.change(screen.getByTestId("audit-actor-input"), {
      target: { value: "user_does_not_exist" },
    });
    fireEvent.click(screen.getByTestId("audit-filter-submit"));

    await waitFor(() =>
      expect(screen.getByText(/暂无匹配的审计事件/)).toBeInTheDocument(),
    );
  });

  it("filters by result", async () => {
    render(
      <TestRouter>
        <AuditEventsPage />
      </TestRouter>,
    );
    await screen.findByTestId("audit-table");

    fireEvent.change(screen.getByTestId("audit-result-select"), {
      target: { value: "BLOCKED" },
    });
    fireEvent.click(screen.getByTestId("audit-filter-submit"));

    await waitFor(() => {
      const table = screen.getByTestId("audit-table");
      const body = within(table);
      expect(body.getByText("DELETION_REQUEST")).toBeInTheDocument();
      expect(body.queryByText("LEGAL_HOLD_PLACE")).not.toBeInTheDocument();
    });
  });
});
