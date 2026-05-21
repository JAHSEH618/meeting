import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { BreakGlassPage } from "../BreakGlassPage";

describe("BreakGlassPage", () => {
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
    expect(screen.getByText("mtg_bg_create")).toBeInTheDocument();
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

  it("approves a pending request after confirm()", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无紧急访问申请|申请列表/);

    fireEvent.click(screen.getByTestId("toggle-create-bg"));
    fireEvent.change(screen.getByTestId("bg-scope-id"), { target: { value: "mtg_bg_approve" } });
    fireEvent.change(screen.getByTestId("bg-reason"), { target: { value: "test" } });
    fireEvent.click(screen.getByTestId("bg-create-submit"));

    const approveBtn = await screen.findByText("批准");
    fireEvent.click(approveBtn);

    await waitFor(() => expect(screen.getByText("已批准")).toBeInTheDocument());
    confirmSpy.mockRestore();
  });

  it("rejects a pending request when reason is provided via prompt()", async () => {
    const promptSpy = vi.spyOn(window, "prompt").mockReturnValue("no justification");
    render(
      <TestRouter>
        <BreakGlassPage />
      </TestRouter>,
    );
    await screen.findByText(/暂无紧急访问申请|申请列表/);

    fireEvent.click(screen.getByTestId("toggle-create-bg"));
    fireEvent.change(screen.getByTestId("bg-scope-id"), { target: { value: "mtg_bg_reject" } });
    fireEvent.change(screen.getByTestId("bg-reason"), { target: { value: "test" } });
    fireEvent.click(screen.getByTestId("bg-create-submit"));

    const rejectBtn = await screen.findByText("拒绝");
    fireEvent.click(rejectBtn);

    await waitFor(() => expect(screen.getByText("已拒绝")).toBeInTheDocument());
    promptSpy.mockRestore();
  });
});
