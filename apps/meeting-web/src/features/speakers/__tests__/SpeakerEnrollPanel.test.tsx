import { afterEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { SpeakerEnrollPanel } from "../SpeakerEnrollPanel";

function renderPanel() {
  const onEnrollSuccess = vi.fn();
  const setError = vi.fn();
  const view = render(
    <SpeakerEnrollPanel
      profileId="spk_test"
      onEnrollSuccess={onEnrollSuccess}
      setError={setError}
    />,
  );
  return { ...view, onEnrollSuccess, setError };
}

function mockEnrollmentStatus(status: "SUCCEEDED" | "FAILED") {
  server.use(
    http.get("/api/speaker-profiles/:profileId/enrollments", () => {
      return HttpResponse.json({
        success: true,
        data: {
          items: [
            {
              enrollmentId: "spe_new",
              speakerProfileId: "spk_test",
              tenantId: "tenant_01",
              sourceAudioFileId: "file_new",
              enrollmentStatus: status,
              qualityScore: status === "SUCCEEDED" ? 0.91 : null,
              modelVersion: status === "SUCCEEDED" ? "deterministic-speaker-v0" : null,
              errorCode: status === "FAILED" ? "VOICEPRINT_LOW_QUALITY" : null,
              createdAt: "2026-05-12T11:00:00Z",
              updatedAt: "2026-05-12T11:01:00Z",
            },
          ],
        },
        error: null,
        requestId: "r",
        traceId: "t",
      });
    }),
  );
}

async function submitUpload() {
  fireEvent.click(screen.getByRole("button", { name: /上传文件/ }));
  const input = document.querySelector<HTMLInputElement>(
    'input[name="speakerEnrollmentAudio"]',
  );
  expect(input).not.toBeNull();
  const file = new File(["voice sample"], "voice.wav", { type: "audio/wav" });
  fireEvent.change(input as HTMLInputElement, { target: { files: [file] } });
  fireEvent.click(screen.getByRole("button", { name: "提交注册" }));
  await screen.findByText(/后端正在提取并注册声纹/);
}

describe("SpeakerEnrollPanel", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("announces successful enrollment inline instead of using alert()", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const alertSpy = vi.spyOn(window, "alert").mockReturnValue(undefined);
    mockEnrollmentStatus("SUCCEEDED");
    const { onEnrollSuccess } = renderPanel();

    await submitUpload();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_600);
    });

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("声纹注册成功"),
    );
    expect(alertSpy).not.toHaveBeenCalled();
    expect(onEnrollSuccess).toHaveBeenCalledOnce();
  });

  it("announces failed enrollment inline instead of using alert()", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const alertSpy = vi.spyOn(window, "alert").mockReturnValue(undefined);
    mockEnrollmentStatus("FAILED");
    const { onEnrollSuccess } = renderPanel();

    await submitUpload();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_600);
    });

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("声纹注册失败"),
    );
    expect(alertSpy).not.toHaveBeenCalled();
    expect(onEnrollSuccess).toHaveBeenCalledOnce();
  });
});
