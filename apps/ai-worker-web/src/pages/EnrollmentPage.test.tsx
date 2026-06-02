import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EnrollmentPage } from "./EnrollmentPage";
import type { PersonDTO } from "@/shared/api/types";

const created: PersonDTO = {
  personId: "p-new",
  displayName: "李四",
  email: null,
  externalId: null,
  createdAt: "2026-05-27T00:00:00Z",
};

vi.mock("@/shared/api/endpoints", () => ({
  searchPersons: vi.fn(async () => []),
  createPerson: vi.fn(async () => created),
  createEnrollmentSession: vi.fn(async (personId: string) => ({
    sessionId: "s1",
    state: "CREATED",
    personId,
  })),
  uploadEnrollmentAudio: vi.fn(),
  previewEnrollment: vi.fn(),
  commitEnrollment: vi.fn(),
}));

describe("EnrollmentPage", () => {
  beforeEach(() => vi.clearAllMocks());

  it("uses personId from the URL when launched from the people workbench", async () => {
    const endpoints = await import("@/shared/api/endpoints");

    renderEnrollmentPage("/enrollment?personId=p-link");
    expect(screen.getByText("p-link").closest(".page-subtitle")).toHaveTextContent("已选择：p-link");
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));

    await waitFor(() => expect(endpoints.createEnrollmentSession).toHaveBeenCalledWith("p-link"));
  });

  it("creates a person from the modal and selects it for session creation", async () => {
    const endpoints = await import("@/shared/api/endpoints");

    renderEnrollmentPage();
    fireEvent.click(screen.getByRole("button", { name: /新建人员/ }));
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await expectSelectedPerson("李四");
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));

    await waitFor(() => expect(endpoints.createEnrollmentSession).toHaveBeenCalledWith("p-new"));
  });

  it("blocks enrollment commit when preview quality is below the business threshold", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.previewEnrollment).mockResolvedValueOnce({
      sessionId: "s1",
      state: "PREVIEWED",
      personId: "p-new",
      qualityScore: 0.49,
    });

    renderEnrollmentPage();
    await createSelectedSession();
    chooseEnrollmentAudio();
    fireEvent.click(screen.getByRole("button", { name: /上传并预览/ }));

    expect(await screen.findByText("质量分 0.49")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /确认录入/ })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /确认录入/ }));
    expect(endpoints.commitEnrollment).not.toHaveBeenCalled();
  });

  it("commits enrollment after a passing quality preview", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.previewEnrollment).mockResolvedValueOnce({
      sessionId: "s1",
      state: "PREVIEWED",
      personId: "p-new",
      qualityScore: 0.72,
    });
    vi.mocked(endpoints.commitEnrollment).mockResolvedValueOnce({
      sessionId: "s1",
      state: "COMMITTED",
      personId: "p-new",
      qualityScore: 0.72,
    });

    renderEnrollmentPage();
    await createSelectedSession();
    chooseEnrollmentAudio();
    fireEvent.click(screen.getByRole("button", { name: /上传并预览/ }));

    expect(await screen.findByText("质量分 0.72")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /确认录入/ }));

    await waitFor(() => expect(endpoints.commitEnrollment).toHaveBeenCalledWith("s1"));
    expect(await screen.findByText(/状态: COMMITTED/)).toBeInTheDocument();
  });
});

function renderEnrollmentPage(path = "/enrollment") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/enrollment" element={<EnrollmentPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function createSelectedSession() {
  fireEvent.click(screen.getByRole("button", { name: /新建人员/ }));
  fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
  fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));
  await expectSelectedPerson("李四");
  fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));
  await screen.findByTestId("session-id");
}

async function expectSelectedPerson(label: string) {
  await waitFor(() => {
    expect(screen.getByText(label).closest(".page-subtitle")).toHaveTextContent(`已选择：${label}`);
  });
}

function chooseEnrollmentAudio() {
  const audioInput = document.getElementById("enrollment-audio-file");
  if (!audioInput) throw new Error("missing enrollment audio input");
  fireEvent.change(audioInput, {
    target: { files: [new File([new Uint8Array(4)], "voice.wav", { type: "audio/wav" })] },
  });
}
