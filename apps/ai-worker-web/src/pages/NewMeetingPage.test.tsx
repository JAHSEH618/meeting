import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/client";
import { NewMeetingPage } from "./NewMeetingPage";

const navigateTarget = vi.fn();

vi.mock("@/shared/api/endpoints", () => ({
  searchPersons: vi.fn(async () => [
    {
      personId: "p1",
      displayName: "李四",
      email: "li@example.com",
      externalId: null,
      createdAt: "2026-06-02T00:00:00Z",
    },
  ]),
  createPerson: vi.fn(async () => ({
    personId: "p-new",
    displayName: "王五",
    email: null,
    externalId: null,
    createdAt: "2026-06-02T00:00:00Z",
  })),
  createMeeting: vi.fn(async () => ({ meetingId: "m1", title: "季度评审", language: "zh", status: "CREATED", createdAt: "" })),
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
    expect(screen.getByLabelText(/语言/)).toHaveAttribute("name", "language");
    expect(screen.getByRole("textbox", { name: "术语" })).toHaveAttribute("name", "glossaryTerm");
    expect(screen.getByRole("textbox", { name: "术语" })).toHaveAttribute("autocomplete", "off");
    expect(screen.getByLabelText(/搜索已有文档/)).toHaveAttribute("name", "documentSearch");
    expect(screen.getByLabelText(/搜索已有文档/)).toHaveAttribute("autocomplete", "off");
    expect(screen.getByPlaceholderText("输入文档标题…")).toBeInTheDocument();
    expect(screen.getByLabelText(/搜索人员/)).toHaveAttribute("name", "personSearch");
    expect(screen.getByLabelText(/搜索人员/)).toHaveAttribute("autocomplete", "off");
    expect(screen.getByPlaceholderText("按姓名 / 邮箱搜索…")).toBeInTheDocument();
    expect(screen.getByLabelText(/参考文档上传/)).toHaveAttribute("name", "referenceDocument");
    expect(document.querySelector('input[name="meetingAudio"]')).toBeInTheDocument();
  });

  it("adds searched and newly created people to the Java meeting participants payload", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    render(
      <MemoryRouter initialEntries={["/meetings/new"]}>
        <Routes><Route path="/meetings/new" element={<NewMeetingPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    fireEvent.change(screen.getByLabelText(/搜索人员/), { target: { value: "李" } });
    await waitFor(() => expect(endpoints.searchPersons).toHaveBeenCalledWith("李", expect.any(Object)));
    fireEvent.click(screen.getByRole("button", { name: "添加 李四" }));
    expect(screen.getByText("li@example.com")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /新建人员/ }));
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "王五" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));
    await waitFor(() => expect(screen.getByText("王五")).toBeInTheDocument());

    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });
    fireEvent.click(screen.getByTestId("start-processing"));

    await waitFor(() => expect(endpoints.createMeeting).toHaveBeenCalled());
    expect(endpoints.createMeeting).toHaveBeenCalledWith(expect.objectContaining({
      participants: [
        { personId: "p1", displayName: "李四", role: "PARTICIPANT" },
        { personId: "p-new", displayName: "王五", role: "PARTICIPANT" },
      ],
    }));
    await waitFor(() => expect(navigateTarget).toHaveBeenCalledWith("/meetings/m1"));
  });

  it("does not add the same participant twice from search results", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    fireEvent.change(screen.getByLabelText(/搜索人员/), { target: { value: "李" } });
    await waitFor(() => expect(endpoints.searchPersons).toHaveBeenCalledWith("李", expect.any(Object)));
    fireEvent.click(screen.getByRole("button", { name: "添加 李四" }));
    expect(screen.getByRole("button", { name: "已添加 李四" })).toBeDisabled();

    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });
    fireEvent.click(screen.getByTestId("start-processing"));

    await waitFor(() => expect(endpoints.createMeeting).toHaveBeenCalled());
    expect(endpoints.createMeeting).toHaveBeenCalledWith(expect.objectContaining({
      participants: [{ personId: "p1", displayName: "李四", role: "PARTICIPANT" }],
    }));
    await waitFor(() => expect(navigateTarget).toHaveBeenCalledWith("/meetings/m1"));
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

  it("keeps a recovery link when audio upload fails after the Java meeting is created", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.initAudioUpload).mockRejectedValueOnce(new ApiError(
      503,
      { code: "AUDIO_UPLOAD_INIT_FAILED", message: "audio upload unavailable", retryable: true },
      "r",
      "t",
    ));
    render(
      <MemoryRouter initialEntries={["/meetings/new"]}>
        <Routes><Route path="/meetings/new" element={<NewMeetingPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });
    fireEvent.click(screen.getByTestId("start-processing"));

    await waitFor(() => expect(endpoints.createMeeting).toHaveBeenCalled());
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "AUDIO_UPLOAD_INIT_FAILED: audio upload unavailable",
    );
    expect(screen.getByRole("link", { name: "查看已创建会议" })).toHaveAttribute("href", "/meetings/m1");
    expect(navigateTarget).not.toHaveBeenCalled();
  });

  it("removes a selected reference document before attaching documents to the Java meeting", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    render(
      <MemoryRouter initialEntries={["/meetings/new"]}>
        <Routes><Route path="/meetings/new" element={<NewMeetingPage />} /></Routes>
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText(/参考文档上传/), {
      target: { files: [new File([new Uint8Array(4)], "ref.pdf", { type: "application/pdf" })] },
    });
    await waitFor(() => expect(endpoints.createDocument).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "移除 ref.pdf" }));
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "季度评审" } });
    const audioInput = document.getElementById("meeting-audio-file");
    if (!audioInput) throw new Error("missing audio input");
    fireEvent.change(audioInput, {
      target: { files: [new File([new Uint8Array(4)], "demo.mp3", { type: "audio/mpeg" })] },
    });
    fireEvent.click(screen.getByTestId("start-processing"));

    await waitFor(() => expect(navigateTarget).toHaveBeenCalledWith("/meetings/m1"));
    expect(endpoints.attachMeetingDocument).not.toHaveBeenCalled();
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

  it("cancels optional reference upload without surfacing an aborted-upload business error", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    let resolveUpload: () => void = () => {};
    globalThis.fetch = vi.fn(async () => new Promise<Response>((resolve) => {
      resolveUpload = () => resolve(new Response("", { status: 200, headers: { etag: '"etag-1"' } }));
    })) as unknown as typeof fetch;
    render(<MemoryRouter><NewMeetingPage /></MemoryRouter>);

    fireEvent.change(screen.getByLabelText(/参考文档上传/), {
      target: { files: [new File([new Uint8Array(4)], "optional.pdf", { type: "application/pdf" })] },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "取消" })).toBeInTheDocument());
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    resolveUpload();

    await waitFor(() => expect(endpoints.abortFileUpload).toHaveBeenCalledWith("doc-up"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/UPLOAD_ABORTED|upload aborted/i)).not.toBeInTheDocument();
  });
});
