import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import type { ApiResponse, ProcessingTask } from "@shared/api/types";
import { TaskProgressPage } from "../TaskProgressPage";

function renderPage() {
  return render(
    <TestRouter initialEntries={["/meetings/mtg_01/tasks/task_01"]}>
      <Routes>
        <Route path="/meetings/:meetingId/tasks/:taskId" element={<TaskProgressPage />} />
      </Routes>
    </TestRouter>,
  );
}

function task(status: ProcessingTask["status"], stepProgress: number): ProcessingTask {
  return {
    taskId: "task_01",
    meetingId: "mtg_01",
    status,
    phase: status === "SUCCEEDED" ? "TERMINAL" : "WORKER_DAG_RUNNING",
    attemptNo: 1,
    currentStep: status === "SUCCEEDED" ? null : "ASR",
    lastErrorCode: null,
    retryable: false,
    steps: [
      { stepName: "ASR", status: status === "SUCCEEDED" ? "SUCCEEDED" : "RUNNING", progress: stepProgress, source: "AI_WORKER_CALLBACK", attemptNo: 1 },
    ],
  };
}

describe("TaskProgressPage", () => {
  it("renders task status, phase, and step progress from snapshot", async () => {
    const { container } = renderPage();

    expect(container.querySelector(".page--workbench")).toBeInTheDocument();
    expect(container.querySelector(".page-hero--workbench")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("音频处理")).toBeInTheDocument());
    expect(screen.getByText("语音识别")).toBeInTheDocument();
    expect(screen.getAllByText("处理中").length).toBeGreaterThan(0);
    expect(screen.getByText("50%")).toBeInTheDocument();
  });

  it("falls back to polling when SSE fails and stops once the task reaches a terminal status", async () => {
    let callCount = 0;
    server.use(
      http.get("/api/processing-tasks/:taskId/events", () => new HttpResponse(null, { status: 500 })),
      http.get("/api/processing-tasks/:taskId", () => {
        callCount += 1;
        const next = callCount >= 2 ? task("SUCCEEDED", 100) : task("RUNNING", 70);
        return HttpResponse.json<ApiResponse<ProcessingTask>>({
          success: true,
          data: next,
          error: null,
          requestId: `req_${callCount}`,
          traceId: `trace_${callCount}`,
        });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText("轮询")).toBeInTheDocument(), { timeout: 4000 });
    await waitFor(() => expect(screen.getAllByText("已完成").length).toBeGreaterThan(0), { timeout: 6000 });
    await waitFor(() => expect(screen.getByText("已结束")).toBeInTheDocument());

    const callsAtTerminal = callCount;
    await new Promise((resolve) => setTimeout(resolve, 3500));
    expect(callCount).toBe(callsAtTerminal);
  }, 15000);
});
