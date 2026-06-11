import { describe, expect, it, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { DocumentsPage } from "../DocumentsPage";

describe("DocumentsPage", () => {
  it("lists existing documents with badges", async () => {
    render(
      <TestRouter>
        <DocumentsPage />
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("Roadmap.pdf")).toBeInTheDocument());
    expect(screen.getByText("INTERNAL")).toBeInTheDocument();
    expect(screen.getByText("PDF")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重新索引" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除" })).toBeInTheDocument();
  });

  it("opens the create form and posts a new document", async () => {
    render(
      <TestRouter>
        <DocumentsPage />
      </TestRouter>,
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
      <TestRouter>
        <DocumentsPage />
      </TestRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "登记文档" }));
    fireEvent.click(screen.getByRole("button", { name: "提交" }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("请填写文档标题和 fileId"),
    );
  });

  it("opens an inline confirmation dialog before deleting", async () => {
    const confirmSpy = vi.spyOn(window, "confirm");
    const deletedDocumentIds: string[] = [];
    server.use(
      http.delete("/api/documents/:documentId", ({ params }) => {
        deletedDocumentIds.push(params.documentId as string);
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
        <DocumentsPage />
      </TestRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "删除" }));

    expect(confirmSpy).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "删除文档" });
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText(/Roadmap\.pdf/)).toBeInTheDocument();

    fireEvent.click(within(dialog).getAllByRole("button", { name: "取消" })[0]);
    expect(screen.queryByRole("dialog", { name: "删除文档" })).not.toBeInTheDocument();
    expect(deletedDocumentIds).toEqual([]);
    expect(screen.getByText("Roadmap.pdf")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "删除文档" })).getByRole("button", {
        name: "确认删除",
      }),
    );

    await waitFor(() => expect(deletedDocumentIds).toEqual(["doc_01"]));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "删除文档" })).not.toBeInTheDocument(),
    );
    confirmSpy.mockRestore();
  });

  it("triggers reindex on click", async () => {
    render(
      <TestRouter>
        <DocumentsPage />
      </TestRouter>,
    );

    await screen.findByText("Roadmap.pdf");
    fireEvent.click(screen.getByRole("button", { name: "重新索引" }));

    // Button transitions to the pending label while the call is in flight,
    // then settles back after reload completes.
    await waitFor(() => expect(screen.getByRole("button", { name: "重新索引" })).toBeEnabled());
  });
});
