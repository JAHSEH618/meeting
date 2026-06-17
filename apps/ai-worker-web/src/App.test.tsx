import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import App from "@/App";
import { createQueryClient } from "@/shared/queries/queryClient";

vi.mock("@/shared/auth/useAuth", () => ({
  useAuth: () => ({ ready: true, token: "token" }),
}));

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
  listSpeakerProfiles: vi.fn(async () => [
    {
      speakerProfileId: "sp1",
      personId: "p1",
      displayName: "李四",
      status: "ACTIVE",
      enrollmentCount: 2,
      lastEnrolledAt: "2026-06-02T00:00:00Z",
      revokedAt: null,
    },
  ]),
  revokeSpeakerProfile: vi.fn(async () => undefined),
  listAdminMeetings: vi.fn(async () => [
    {
      meetingId: "m1",
      title: "Python 工作站联调",
      status: "PROCESSING",
      language: "zh-CN",
      createdAt: "2026-06-17T08:30:00Z",
    },
  ]),
}));

describe("App routes", () => {
  it("exposes the dedicated speaker-profile management page", async () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/speaker-profiles"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByRole("link", { name: "声纹档案" })).toHaveAttribute("href", "/speaker-profiles");
    expect(await screen.findByRole("heading", { name: "声纹档案" })).toBeInTheDocument();
    expect(await screen.findByText("李四")).toBeInTheDocument();
  });

  it("exposes the dedicated people workbench", async () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/people?q=%E6%9D%8E"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByRole("link", { name: "人员" })).toHaveAttribute("href", "/people");
    expect(await screen.findByRole("heading", { name: "人员" })).toBeInTheDocument();
    expect(await screen.findByText("li@example.com")).toBeInTheDocument();
  });

  it("renders the workstation entry without a brand mark", async () => {
    const { container } = render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/meetings"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(container.querySelector(".layout__brand")).not.toBeInTheDocument();
    expect(container.querySelector(".workstation-modules")).toBeInTheDocument();
    expect(await screen.findByText("处理链路")).toBeInTheDocument();
    expect(screen.getByText("声纹档案")).toBeInTheDocument();
    expect(await screen.findByText("Python 工作站联调")).toBeInTheDocument();
  });
});
