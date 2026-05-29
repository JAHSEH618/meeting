import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("creates a person from the modal and selects it for session creation", async () => {
    const endpoints = await import("@/shared/api/endpoints");

    render(<EnrollmentPage />);
    fireEvent.click(screen.getByRole("button", { name: /新建人员/ }));
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(screen.getByText(/已选择：李四/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /创建录入会话/ }));

    await waitFor(() => expect(endpoints.createEnrollmentSession).toHaveBeenCalledWith("p-new"));
  });
});
