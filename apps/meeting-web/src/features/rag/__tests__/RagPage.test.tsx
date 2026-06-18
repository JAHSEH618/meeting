import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { RagPage } from "../RagPage";
import type { ApiResponse, RagQueryRequest } from "@shared/api/types";

async function selectProductMeeting() {
  fireEvent.click(await screen.findByRole("button", { name: /选择产品周会/ }));
}

describe("RagPage", () => {
  it("starts with meeting selection before showing the question form", async () => {
    const { container } = render(
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    expect(container.querySelector(".page--workbench")).toBeInTheDocument();
    expect(container.querySelector(".glass-panel")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "选择会议" })).toBeInTheDocument();
    expect(screen.queryByLabelText("rag-question-input")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "提问" })).not.toBeInTheDocument();

    await selectProductMeeting();

    expect(screen.getByText("已选择会议：产品周会")).toBeInTheDocument();
    expect(screen.getByLabelText("rag-question-input")).toBeInTheDocument();
    expect((screen.getByLabelText("检索条数") as HTMLInputElement).value).toBe("8");
    expect(screen.getByText("当前会议")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提问" })).toBeEnabled();
  });

  it("rejects an empty question", async () => {
    render(
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    await selectProductMeeting();
    fireEvent.click(screen.getByRole("button", { name: "提问" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("请先输入问题"));
  });

  it("sends the selected meeting as the required query scope", async () => {
    const requestBodies: RagQueryRequest[] = [];
    server.use(
      http.post("/api/rag/query", async ({ request }) => {
        requestBodies.push((await request.json()) as RagQueryRequest);
        return HttpResponse.json<ApiResponse<unknown>>({
          success: true,
          data: {
            answer: "会议范围回答",
            citations: [],
            coverage: "TRANSCRIPT_ONLY",
            artifactManifestId: "llmlog_selected_meeting",
          },
          error: null,
          requestId: "req_selected_meeting",
          traceId: "trace_selected_meeting",
        });
      }),
    );

    render(
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    await selectProductMeeting();
    fireEvent.change(screen.getByLabelText("rag-question-input"), {
      target: { value: "这场会结论是什么？" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));

    await waitFor(() => expect(requestBodies).toHaveLength(1));
    expect(requestBodies[0]?.scope.meetingIds).toEqual(["mtg_01"]);
    expect(requestBodies[0]?.scope.documentIds).toEqual([]);
  });

  it("renders the answer with coverage badge and a meeting+document citation", async () => {
    render(
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    await selectProductMeeting();
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
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    await selectProductMeeting();
    fireEvent.change(screen.getByLabelText("rag-question-input"), {
      target: { value: "(empty) what about something we have no data for?" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));

    await screen.findByLabelText("rag-answer");
    expect(screen.getByText("无引用，仅供参考")).toBeInTheDocument();
    expect(screen.getByText(/模型未明确指明引用来源/)).toBeInTheDocument();
  });

  it("citation meeting link contains startMs deep-link param", async () => {
    render(
      <TestRouter>
        <RagPage />
      </TestRouter>,
    );

    await selectProductMeeting();
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
