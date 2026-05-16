import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { RagPage } from "../RagPage";

describe("RagPage", () => {
  it("renders the question form with default topN and scope summary", async () => {
    render(
      <MemoryRouter>
        <RagPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByLabelText("topN")).toBeInTheDocument());
    expect((screen.getByLabelText("topN") as HTMLInputElement).value).toBe("8");
    expect(screen.getByText("全部可读范围")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提问" })).toBeEnabled();
  });

  it("rejects an empty question", async () => {
    render(
      <MemoryRouter>
        <RagPage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "提问" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("请先输入问题"));
  });

  it("renders the answer with coverage badge and a meeting+document citation", async () => {
    render(
      <MemoryRouter>
        <RagPage />
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText("rag-question-input"), {
      target: { value: "下周做什么？" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));

    const answerCard = await screen.findByLabelText("rag-answer");
    expect(screen.getByLabelText("rag-coverage")).toHaveTextContent("会议 + 文档");
    expect(answerCard).toHaveTextContent("产品周会");
    expect(answerCard).toHaveTextContent("Roadmap.pdf");
    expect(screen.getByText("跳转到转写片段 →")).toBeInTheDocument();
  });

  it("shows the degraded notice when the model returns no citations", async () => {
    render(
      <MemoryRouter>
        <RagPage />
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText("rag-question-input"), {
      target: { value: "(empty) what about something we have no data for?" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));

    await screen.findByLabelText("rag-answer");
    expect(screen.getByText("无引用 — 仅供参考")).toBeInTheDocument();
    expect(screen.getByText(/模型未明确指明引用来源/)).toBeInTheDocument();
  });

  it("citation meeting link contains startMs deep-link param", async () => {
    render(
      <MemoryRouter>
        <RagPage />
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText("rag-question-input"), {
      target: { value: "问题" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));

    const link = (await screen.findByText("跳转到转写片段 →")) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toContain("startMs=12000");
    expect(link.getAttribute("href")).toContain("segmentId=seg_01");
    expect(link.getAttribute("href")).toContain("/meetings/mtg_01/transcript");
  });
});
