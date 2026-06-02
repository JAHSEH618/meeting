import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/client";
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
    meeting: {
      success: true,
      data: {
        meetingId: "m1",
        title: "季度评审",
        status: "RUNNING",
        securityLevel: "INTERNAL",
        language: "zh",
        transcriptVersion: 3,
        participants: [{ personId: "p1", displayName: "李四", role: "PARTICIPANT" }],
        createdAt: "",
      },
    },
    latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
    speakers: {
      success: true,
      data: [{
        speakerLabel: "SPEAKER_01",
        displayName: "李四",
        personId: "p1",
        speakerProfileId: "sp1",
        confirmationStatus: "AUTO_CONFIRMED",
        autoMatchScore: 0.91,
        confirmedAt: "2026-05-27T00:00:00Z",
        candidatePersonIds: ["p1", "p2"],
        candidates: [
          { personId: "p1", speakerProfileId: "sp1", displayName: "李四", confidence: 0.91 },
          { personId: "p2", speakerProfileId: "sp2", displayName: "王五", confidence: 0.72 },
        ],
      }],
    },
    minutes: { success: true, data: { title: "纪要", markdown: "# 会议纪要\n\n完成。", minutesVersion: 1 } },
  })),
  confirmSpeaker: vi.fn(async () => ({
    speakerLabel: "SPEAKER_01",
    displayName: "李四",
    personId: "p1",
    speakerProfileId: "sp1",
    confirmationStatus: "MANUALLY_CONFIRMED",
    autoMatchScore: 0.91,
    confirmedAt: "2026-05-27T00:00:00Z",
    candidatePersonIds: ["p1", "p2"],
    candidates: [],
  })),
  rejectSpeaker: vi.fn(async () => undefined),
  searchPersons: vi.fn(async () => [
    { personId: "p1", displayName: "李四", email: null, externalId: null, createdAt: "" },
    { personId: "p2", displayName: "王五", email: "wang@example.com", externalId: null, createdAt: "" },
  ]),
  updateMeeting: vi.fn(async () => ({
    meetingId: "m1",
    title: "季度评审",
    status: "RUNNING",
    securityLevel: "INTERNAL",
    language: "zh",
    transcriptVersion: 3,
    participants: [
      { personId: "p1", displayName: "李四", role: "PARTICIPANT" },
      { personId: "p2", displayName: "王五", role: "PARTICIPANT" },
    ],
    createdAt: "",
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

    const speakers = await screen.findByRole("region", { name: "说话人" });
    expect(within(speakers).getByText(/SPEAKER_01/)).toBeInTheDocument();
    expect(within(speakers).getByText("李四")).toBeInTheDocument();
    expect(within(speakers).getByText(/自动认定/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "会议纪要" })).toBeInTheDocument();
    expect(screen.getByTestId("step-ASR")).toHaveTextContent("SUCCEEDED");
  });

  it("does not label manual or legacy confirmed speakers as auto confirmed", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "SUCCEEDED", phase: "TERMINAL", attemptNo: 1, currentStep: null, lastErrorCode: null, retryable: false, steps: [] } },
      speakers: {
        success: true,
        data: [
          {
            speakerLabel: "SPEAKER_AUTO",
            displayName: "李四",
            personId: "p1",
            speakerProfileId: "sp1",
            confirmationStatus: "AUTO_CONFIRMED",
            autoMatchScore: 0.91,
            confirmedAt: "2026-05-27T00:00:00Z",
            candidatePersonIds: ["p1"],
            candidates: [],
          },
          {
            speakerLabel: "SPEAKER_MANUAL",
            displayName: "王五",
            personId: "p2",
            speakerProfileId: "sp2",
            confirmationStatus: "MANUALLY_CONFIRMED",
            autoMatchScore: 0.73,
            confirmedAt: "2026-05-27T00:00:00Z",
            candidatePersonIds: ["p2"],
            candidates: [{ personId: "p2", speakerProfileId: "sp2", displayName: "王五", confidence: 0.73 }],
          },
          {
            speakerLabel: "SPEAKER_LEGACY",
            displayName: "赵六",
            personId: "p3",
            speakerProfileId: null,
            confirmationStatus: "CONFIRMED",
            autoMatchScore: null,
            confirmedAt: "2026-05-27T00:00:00Z",
            candidatePersonIds: ["p3"],
            candidates: [{ personId: "p3", speakerProfileId: "sp3", displayName: "赵六", confidence: 0.68 }],
          },
        ],
      },
      minutes: null,
    });

    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await screen.findByText("SPEAKER_AUTO");

    expect(screen.getByText("李四").parentElement).toHaveTextContent("自动认定");
    expect(screen.getByText("王五").parentElement).toHaveTextContent("人工认定");
    expect(screen.getByText("王五").parentElement).not.toHaveTextContent("自动认定");
    expect(screen.getByText("赵六").parentElement).toHaveTextContent("已认定");
    expect(screen.getByText("赵六").parentElement).not.toHaveTextContent("自动认定");
    expect(screen.queryByRole("button", { name: "认定 王五 0.73" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "认定 赵六 0.68" })).not.toBeInTheDocument();
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

  it("lists meeting participants and adds a new person with the current version", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    const participants = await screen.findByRole("region", { name: "参会人" });
    expect(within(participants).getByText("李四")).toBeInTheDocument();

    fireEvent.change(within(participants).getByLabelText("搜索人员"), { target: { value: "王" } });
    const addWang = await within(participants).findByRole("button", { name: "添加 王五" });

    expect(within(participants).getByRole("button", { name: "已添加 李四" })).toBeDisabled();

    fireEvent.click(addWang);

    await waitFor(() => expect(endpoints.updateMeeting).toHaveBeenCalledWith("m1", {
      participants: [
        { personId: "p1", displayName: "李四", role: "PARTICIPANT" },
        { personId: "p2", displayName: "王五", role: "PARTICIPANT" },
      ],
      expectedVersion: 3,
    }));
    await waitFor(() => expect(endpoints.getMeetingAggregate).toHaveBeenCalledTimes(2));
  });

  it("confirms a speaker candidate with the current transcript version", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
      speakers: {
        success: true,
        data: [{
          speakerLabel: "SPEAKER_01",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "CANDIDATE",
          autoMatchScore: 0.91,
          confirmedAt: null,
          candidatePersonIds: ["p1", "p2"],
          candidates: [
            { personId: "p1", speakerProfileId: "sp1", displayName: "李四", confidence: 0.91 },
            { personId: "p2", speakerProfileId: "sp2", displayName: "王五", confidence: 0.72 },
          ],
        }],
      },
      minutes: { success: true, data: { title: "纪要", markdown: "# 会议纪要\n\n完成。", minutesVersion: 1 } },
    });
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await screen.findByRole("button", { name: "认定 李四 0.91" });
    fireEvent.click(screen.getByRole("button", { name: "认定 李四 0.91" }));

    await waitFor(() => expect(endpoints.confirmSpeaker).toHaveBeenCalledWith("m1", "SPEAKER_01", {
      personId: "p1",
      speakerProfileId: "sp1",
      expectedTranscriptVersion: 3,
    }));
    await waitFor(() => expect(endpoints.getMeetingAggregate).toHaveBeenCalledTimes(2));
  });

  it("requires confirmation before rejecting a speaker candidate set", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
      speakers: {
        success: true,
        data: [{
          speakerLabel: "SPEAKER_02",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "CANDIDATE",
          autoMatchScore: 0.62,
          confirmedAt: null,
          candidatePersonIds: ["p2"],
          candidates: [{ personId: "p2", speakerProfileId: "sp2", displayName: "王五", confidence: 0.62 }],
        }],
      },
      minutes: null,
    });
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "驳回 SPEAKER_02" }));

    expect(screen.getByRole("dialog", { name: "驳回说话人候选" })).toBeInTheDocument();
    expect(screen.getByText(/将保留原始 SPEAKER 标签/)).toBeInTheDocument();
    expect(endpoints.rejectSpeaker).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "确认驳回" }));

    await waitFor(() => expect(endpoints.rejectSpeaker).toHaveBeenCalledWith("m1", "SPEAKER_02"));
    await waitFor(() => expect(endpoints.getMeetingAggregate).toHaveBeenCalledTimes(2));
  });

  it("shows speaker rejection failures inside the confirmation dialog", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
      speakers: {
        success: true,
        data: [{
          speakerLabel: "SPEAKER_02",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "CANDIDATE",
          autoMatchScore: 0.62,
          confirmedAt: null,
          candidatePersonIds: ["p2"],
          candidates: [{ personId: "p2", speakerProfileId: "sp2", displayName: "王五", confidence: 0.62 }],
        }],
      },
      minutes: null,
    });
    vi.mocked(endpoints.rejectSpeaker).mockRejectedValueOnce(new ApiError(
      409,
      { code: "SPEAKER_REJECT_CONFLICT", message: "speaker state changed", retryable: false },
      "r",
      "t",
    ));
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "驳回 SPEAKER_02" }));
    fireEvent.click(screen.getByRole("button", { name: "确认驳回" }));

    const dialog = await screen.findByRole("dialog", { name: "驳回说话人候选" });
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "SPEAKER_REJECT_CONFLICT: speaker state changed",
    );
  });

  it("shows rejected speaker rows as final and hides stale candidate actions", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "SUCCEEDED", phase: "TERMINAL", attemptNo: 1, currentStep: null, lastErrorCode: null, retryable: false, steps: [] } },
      speakers: {
        success: true,
        data: [{
          speakerLabel: "SPEAKER_03",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "REJECTED",
          autoMatchScore: 0.58,
          confirmedAt: null,
          candidatePersonIds: ["p3"],
          candidates: [{ personId: "p3", speakerProfileId: "sp3", displayName: "赵六", confidence: 0.58 }],
        }],
      },
      minutes: null,
    });

    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await screen.findByText("SPEAKER_03");

    expect(screen.getByText("SPEAKER_03").parentElement).toHaveTextContent("已驳回");
    expect(screen.queryByRole("button", { name: "认定 赵六 0.58" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "驳回 SPEAKER_03" })).not.toBeInTheDocument();
  });

  it("surfaces Java transcript-version conflicts during speaker confirmation", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.getMeetingAggregate).mockResolvedValueOnce({
      meeting: { success: true, data: { meetingId: "m1", title: "季度评审", status: "RUNNING", securityLevel: "INTERNAL", language: "zh", transcriptVersion: 3, createdAt: "" } },
      latestTask: { success: true, data: { taskId: "task1", meetingId: "m1", status: "RUNNING", phase: "WORKER_DAG_RUNNING", attemptNo: 1, currentStep: "ASR", lastErrorCode: null, retryable: true, steps: [] } },
      speakers: {
        success: true,
        data: [{
          speakerLabel: "SPEAKER_01",
          displayName: null,
          personId: null,
          speakerProfileId: null,
          confirmationStatus: "CANDIDATE",
          autoMatchScore: 0.91,
          confirmedAt: null,
          candidatePersonIds: ["p1"],
          candidates: [{ personId: "p1", speakerProfileId: "sp1", displayName: "李四", confidence: 0.91 }],
        }],
      },
      minutes: { success: true, data: { title: "纪要", markdown: "# 会议纪要\n\n完成。", minutesVersion: 1 } },
    });
    vi.mocked(endpoints.confirmSpeaker).mockRejectedValueOnce(new ApiError(
      409,
      { code: "TRANSCRIPT_VERSION_CONFLICT", message: "transcript changed", retryable: false },
      "r",
      "t",
    ));
    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "认定 李四 0.91" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("TRANSCRIPT_VERSION_CONFLICT: transcript changed");
  });

  it("uses a typographic ellipsis for export loading state", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.pollExport).mockImplementationOnce(() => new Promise(() => undefined));

    render(
      <MemoryRouter initialEntries={["/meetings/m1"]}>
        <Routes><Route path="/meetings/:meetingId" element={<MeetingDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    await screen.findByTestId("export-docx");
    fireEvent.click(screen.getByTestId("export-docx"));

    expect(screen.getByRole("button", { name: "导出中…" })).toBeInTheDocument();
    await waitFor(() => expect(endpoints.pollExport).toHaveBeenCalled());
  });
});
