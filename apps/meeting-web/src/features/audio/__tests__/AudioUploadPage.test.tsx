import { afterEach, describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import type {
  ApiResponse,
  AudioUploadSession,
  ProcessingTask,
} from "@shared/api/types";
import { AudioUploadPage } from "../AudioUploadPage";

const STORAGE_KEY = "meeting.audioUpload.mtg_01";

afterEach(() => {
  window.localStorage.removeItem(STORAGE_KEY);
});

function renderPage() {
  return render(
    <TestRouter initialEntries={["/meetings/mtg_01/audio"]}>
      <Routes>
        <Route path="/meetings/:meetingId/audio" element={<AudioUploadPage />} />
        <Route path="/meetings/:meetingId/tasks/:taskId" element={<div>task progress loaded</div>} />
      </Routes>
    </TestRouter>,
  );
}

describe("AudioUploadPage", () => {
  it("uploads a small audio file and navigates to task progress", async () => {
    renderPage();

    const file = new File([new Uint8Array([1, 2, 3, 4])], "standup.wav", { type: "audio/wav" });
    fireEvent.change(screen.getByLabelText("音频文件"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "开始上传" }));

    await waitFor(() => expect(screen.getByText("task progress loaded")).toBeInTheDocument());
  });

  it("rejects resume when the re-selected file fingerprint mismatches", async () => {
    window.localStorage.setItem(STORAGE_KEY, "upl_resume");
    const session: AudioUploadSession = {
      uploadId: "upl_resume",
      meetingId: "mtg_01",
      uploadStatus: "UPLOADING",
      expiresAt: "2026-05-15T09:00:00Z",
      partSizeBytes: 8 * 1024 * 1024,
      maxPartCount: 10000,
      objectKey: "tenant/tenant_01/meeting/mtg_01/upload/upl_resume/raw",
      bucket: "meeting-audio-auska",
      contentType: "audio/wav",
      fileName: "standup.wav",
      fileSizeBytes: 4,
      fileSha256: "f".repeat(64),
      parts: [],
    };
    server.use(
      http.get("/api/meetings/:meetingId/files/audio/uploads/:uploadId", () =>
        HttpResponse.json<ApiResponse<AudioUploadSession>>({
          success: true,
          data: session,
          error: null,
          requestId: "req_get",
          traceId: "trace_get",
        }),
      ),
    );

    renderPage();
    await waitFor(() => expect(screen.getByText("已建立")).toBeInTheDocument());

    const file = new File([new Uint8Array([9, 9, 9, 9])], "standup.wav", { type: "audio/wav" });
    fireEvent.change(screen.getByLabelText("音频文件"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "继续上传" }));

    await waitFor(() =>
      expect(screen.getByText("文件指纹与原上传会话不一致，请选择同一个文件")).toBeInTheDocument(),
    );
  });

  it("rejects files larger than the 2 GiB single-PUT cap before hashing", async () => {
    renderPage();

    const file = new File([new Uint8Array([1, 2, 3, 4])], "huge.wav", { type: "audio/wav" });
    // jsdom's File implementation honors a writable `size` getter; spoof
    // a 3 GiB file without actually allocating 3 GiB of bytes.
    Object.defineProperty(file, "size", { value: 3 * 1024 * 1024 * 1024 });
    fireEvent.change(screen.getByLabelText("音频文件"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "开始上传" }));

    await waitFor(() =>
      expect(screen.getByText("音频文件超过 2 GiB 单 PUT 上限")).toBeInTheDocument(),
    );
  });

  it("navigates straight to task progress when the saved session is already COMPLETED", async () => {
    window.localStorage.setItem(STORAGE_KEY, "upl_done");
    const session: AudioUploadSession = {
      uploadId: "upl_done",
      meetingId: "mtg_01",
      uploadStatus: "COMPLETED",
      expiresAt: "2026-05-15T09:00:00Z",
      partSizeBytes: 8 * 1024 * 1024,
      maxPartCount: 10000,
      objectKey: "tenant/tenant_01/meeting/mtg_01/upload/upl_done/raw",
      bucket: "meeting-audio-auska",
      contentType: "audio/wav",
      fileName: "standup.wav",
      fileSizeBytes: 4,
      fileSha256: "a".repeat(64),
      fileId: "file_done",
      parts: [],
    };
    const task: ProcessingTask = {
      taskId: "task_done",
      meetingId: "mtg_01",
      status: "RUNNING",
      phase: "WORKER_DAG_RUNNING",
      attemptNo: 1,
      currentStep: "ASR",
      lastErrorCode: null,
      retryable: false,
      steps: [],
    };
    server.use(
      http.get("/api/meetings/:meetingId/files/audio/uploads/:uploadId", () =>
        HttpResponse.json<ApiResponse<AudioUploadSession>>({
          success: true,
          data: session,
          error: null,
          requestId: "req_done",
          traceId: "trace_done",
        }),
      ),
      http.get("/api/meetings/:meetingId/processing-tasks/latest", () =>
        HttpResponse.json<ApiResponse<ProcessingTask>>({
          success: true,
          data: task,
          error: null,
          requestId: "req_latest",
          traceId: "trace_latest",
        }),
      ),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText("task progress loaded")).toBeInTheDocument());
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
