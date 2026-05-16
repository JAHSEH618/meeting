import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { DocumentsPage } from "../DocumentsPage";

describe("DocumentsPage", () => {
  it("lists existing documents with badges", async () => {
    render(
      <MemoryRouter>
        <DocumentsPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("Roadmap.pdf")).toBeInTheDocument());
    expect(screen.getByText("INTERNAL")).toBeInTheDocument();
    expect(screen.getByText("PDF")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重新索引" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除" })).toBeInTheDocument();
  });

  it("opens the create form and posts a new document", async () => {
    render(
      <MemoryRouter>
        <DocumentsPage />
      </MemoryRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "登记文档" }));

    fireEvent.change(screen.getByPlaceholderText("例：2026 Q2 路线图"), {
      target: { value: "测试文档" },
    });
    fireEvent.change(screen.getByPlaceholderText("例：file_doc_abc"), {
      target: { value: "file_test" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "提交" })).not.toBeInTheDocument());
  });

  it("rejects empty title or fileId in the create form", async () => {
    render(
      <MemoryRouter>
        <DocumentsPage />
      </MemoryRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "登记文档" }));
    fireEvent.click(screen.getByRole("button", { name: "提交" }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("请填写文档标题和 fileId"),
    );
  });

  it("confirms before deleting", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <MemoryRouter>
        <DocumentsPage />
      </MemoryRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "删除" }));

    expect(confirmSpy).toHaveBeenCalledOnce();
    // List unchanged because user cancelled.
    expect(screen.getByText("Roadmap.pdf")).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it("triggers reindex on click", async () => {
    render(
      <MemoryRouter>
        <DocumentsPage />
      </MemoryRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "重新索引" }));

    // Button transitions to the pending label while the call is in flight,
    // then settles back after reload completes.
    await waitFor(() => expect(screen.getByRole("button", { name: "重新索引" })).toBeEnabled());
  });
});
