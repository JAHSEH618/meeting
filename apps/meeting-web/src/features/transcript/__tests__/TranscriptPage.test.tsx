import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { TranscriptPage } from "../TranscriptPage";
import { server } from "@shared/api/mocks/server";
import type { ApiResponse } from "@shared/api/types";

describe("TranscriptPage", () => {
  it("renders transcript segments and task link", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/transcript"]}>
        <Routes>
          <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("今天先确认阶段二验收范围。")).toBeInTheDocument());
    expect(screen.getByText("SPEAKER_00")).toBeInTheDocument();
    expect(screen.getByText("任务进度")).toBeInTheDocument();
  });

  it("edits a segment and surfaces the downstream STALE banner", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/transcript"]}>
        <Routes>
          <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const segmentRow = await screen.findByText("今天先确认阶段二验收范围。");
    const article = segmentRow.closest("article");
    if (!article) throw new Error("segment article not rendered");

    fireEvent.click(within(article).getByRole("button", { name: "编辑" }));
    const textarea = await screen.findByLabelText(/编辑片段/);
    fireEvent.change(textarea, { target: { value: "确认阶段二验收口径。" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(screen.getByText(/下游纪要、待办、决策、风险与 RAG chunk 已标记为 STALE/)).toBeInTheDocument(),
    );
  });

  it("shows a version-conflict message and reloads when the server returns 409", async () => {
    server.use(
      http.patch("/api/meetings/:meetingId/transcript/segments/:segmentId", () =>
        HttpResponse.json<ApiResponse>(
          {
            success: false,
            data: null,
            error: {
              code: "VERSION_CONFLICT",
              message: "internal mismatch",
              retryable: false,
              details: { expectedVersion: 1, actualVersion: 2 },
            },
            requestId: "req_conflict",
            traceId: "trace_conflict",
          },
          { status: 409 },
        ),
      ),
    );

    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/transcript"]}>
        <Routes>
          <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const segmentRow = await screen.findByText("今天先确认阶段二验收范围。");
    const article = segmentRow.closest("article")!;
    fireEvent.click(within(article).getByRole("button", { name: "编辑" }));
    fireEvent.change(await screen.findByLabelText(/编辑片段/), { target: { value: "其它内容" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(screen.getByText(/内容已被更新；已自动刷新到最新版本/)).toBeInTheDocument(),
    );
  });
});
