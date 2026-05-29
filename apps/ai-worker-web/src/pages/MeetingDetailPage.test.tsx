import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MeetingDetailPage } from "./MeetingDetailPage";
import type { TaskEventDTO } from "@/shared/api/types";

let taskEventHandler: ((event: TaskEventDTO) => void) | null = null;
const streamClose = vi.fn();

vi.mock("@/shared/api/client", async () => {
  const actual = await vi.importActual<typeof import("@/shared/api/client")>("@/shared/api/client");
  return {
    ...actual,
    subscribeEventStream: vi.fn((_url: string, handlers: { onEvent: (event: TaskEventDTO) => void }) => {
      taskEventHandler = handlers.onEvent;
      return { close: streamClose };
    }),
  };
});

vi.mock("@/shared/api/endpoints", () => ({
  getMeetingAggregate: vi.fn(async () => ({
    meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", createdAt: "" } },
    latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
    speakers: {
      success: true,
      data: [{
        speakerLabel: "SPEAKER_01",
        displayName: "李四",
        personId: "p1",
        speakerProfileId: "sp1",
        confirmationStatus: "CONFIRMED",
        autoMatchScore: 0.91,
        confirmedAt: "2026-05-27T00:00:00Z",
        candidatePersonIds: ["p1", "p2"],
      }],
    },
    minutes: { success: true, data: { title: "纪要", markdown: "# 会议纪要\n\n完成。", minutesVersion: 1 } },
  })),
  createExport: vi.fn(async () => ({ exportId: "exp1", status: "RUNNING", format: "DOCX" })),
  pollExport: vi.fn(async () => ({ exportId: "exp1", status: "SUCCEEDED", format: "DOCX", downloadUrl: "https://download/docx" })),
  processingTaskEventsUrl: vi.fn((taskId: string) => `/api/processing-tasks/${encodeURIComponent(taskId)}/events`),
}));

describe("MeetingDetailPage", () => {
  beforeEach(() => {
    taskEventHandler = null;
    streamClose.mockClear();
    vi.clearAllMocks();
  });

  it("renders pipeline steps, speakers, and safe minutes markdown", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId("step-ASR")).toBeInTheDocument());
    await waitFor(() => expect(taskEventHandler).not.toBeNull());
    act(() => {
      taskEventHandler?.({ taskId: "task1", steps: [{ stepName: "ASR", status: "SUCCEEDED", progress: 100 }] });
    });

    expect(await screen.findByText(/SPEAKER_01/)).toBeInTheDocument();
    expect(screen.getByText("李四")).toBeInTheDocument();
    expect(screen.getByText(/自动认定/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "会议纪要" })).toBeInTheDocument();
    expect(screen.getByTestId("step-ASR")).toHaveTextContent("SUCCEEDED");
  });

  it("creates and polls export download links", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await screen.findByTestId("export-docx");
    fireEvent.click(screen.getByTestId("export-docx"));

    await waitFor(() => expect(screen.getByTestId("download-link")).toHaveAttribute("href", "https://download/docx"));
  });
});
