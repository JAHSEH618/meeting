import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/client";
import { NewMeetingPage } from "./NewMeetingPage";

const navigateTarget = vi.fn();

vi.mock("@/shared/api/endpoints", () => ({
  createMeeting: vi.fn(async () => ({ meetingId: "m1", title: "季度评审", securityLevel: "INTERNAL", language: "zh", status: "CREATED", createdAt: "" })),
  updateMeetingGlossary: vi.fn(async () => undefined),
  attachMeetingDocument: vi.fn(async () => undefined),
  initFileUpload: vi.fn(async () => ({ uploadId: "doc-up", parts: [{ partNumber: 1, uploadUrl: "https://upload/doc", expiresAt: "", headers: {} }] })),
  createFileUploadPart: vi.fn(),
  completeFileUpload: vi.fn(async () => ({ fileId: "file-doc", sha256: "a", sizeBytes: 4, contentType: "application/pdf" })),
  abortFileUpload: vi.fn(),
  createDocument: vi.fn(async () => ({ documentId: "doc1", title: "ref.pdf" })),
  initAudioUpload: vi.fn(async () => ({ uploadId: "audio-up", parts: [{ partNumber: 1, uploadUrl: "https://upload/audio", expiresAt: "", headers: {} }] })),
  createAudioUploadPart: vi.fn(),
  completeAudioUpload: vi.fn(async () => ({ uploadId: "audio-up", uploadStatus: "COMPLETED", fileId: "audio-file" })),
  abortAudioUpload: vi.fn(),
  searchDocuments: vi.fn(async () => []),
}));

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => navigateTarget };
});

describe("NewMeetingPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.fetch = vi.fn(async () => new Response("", { status: 200, headers: { etag: '"etag-1"' } })) as unknown as typeof fetch;
  });

  it("requires title and audio before start", () => {
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);
    expect(screen.getByTestId("start-processing")).toBeDisabled();
  });

  it("names non-auth form controls and disables browser autocomplete", () => {
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);

    expect(screen.getByLabelText(/标题/)).toHaveAttribute("name", "title");
    expect(screen.getByLabelText(/标题/)).toHaveAttribute("autocomplete", "off");
    expect(screen.getByLabelText(/安全级别/)).toHaveAttribute("name", "securityLevel");
    expect(screen.getByLabelText(/语言/)).toHaveAttribute("name", "language");
    expect(screen.getByRole("textbox", { name: "术语" })).toHaveAttribute("name", "glossaryTerm");
    expect(screen.getByRole("textbox", { name: "术语" })).toHaveAttribute("autocomplete", "off");
    expect(screen.getByLabelText(/搜索已有文档/)).toHaveAttribute("name", "documentSearch");
    expect(screen.getByLabelText(/搜索已有文档/)).toHaveAttribute("autocomplete", "off");
    expect(screen.getByPlaceholderText("输入文档标题…")).toBeInTheDocument();
    expect(screen.getByLabelText(/参考文档上传/)).toHaveAttribute("name", "referenceDocument");
    expect(document.querySelector('input[name="meetingAudio"]')).toBeInTheDocument();
  });

  it("uploads docs immediately, starts meeting orchestration, and navigates to detail", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    render(
      <MemoryRouter initialEntries={["/meetings/new"]}>
        <Routes><Route path="/meetings/new" element={<NewMeetingPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    const termInput = screen.getByPlaceholderText(/按 Enter 添加术语/);
    fireEvent.change(termInput, { target: { value: "LLM" } });
    fireEvent.keyDown(termInput, { key: "Enter" });
    fireEvent.change(screen.getByLabelText(/参考文档上传/), {
      target: { files: [new File([new Uint8Array(4)], "ref.pdf", { type: "application/pdf" })] },
    });
    await waitFor(() => expect(endpoints.createDocument).toHaveBeenCalled());
    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });
    fireEvent.click(screen.getByTestId("start-processing"));

    await waitFor(() => expect(navigateTarget).toHaveBeenCalledWith("/meetings/m1"));
    expect(endpoints.createMeeting).toHaveBeenCalledWith(expect.objectContaining({ title: "季度评审" }));
    expect(endpoints.updateMeetingGlossary).toHaveBeenCalledWith("m1", [{ term: "LLM", aliases: [] }]);
    expect(endpoints.attachMeetingDocument).toHaveBeenCalledWith("m1", { documentId: "doc1", role: "REFERENCE" });
    expect(endpoints.completeAudioUpload).toHaveBeenCalled();
  });

  it("surfaces backend document upload MIME errors with the business error code", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.initFileUpload).mockRejectedValueOnce(new ApiError(
      415,
      { code: "FILE_MIME_NOT_ALLOWED", message: "unsupported file type", retryable: false },
      "r",
      "t",
    ));
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);

    fireEvent.change(screen.getByLabelText(/参考文档上传/), {
      target: { files: [new File([new Uint8Array(4)], "blocked.exe", { type: "application/x-msdownload" })] },
    });

    await waitFor(() => {
      expect(screen.getAllByText(/FILE_MIME_NOT_ALLOWED: unsupported file type/).length).toBeGreaterThan(0);
    });
  });
});
