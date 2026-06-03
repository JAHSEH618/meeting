import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "@/shared/queries/queryClient";
import { SpeakerProfilesPage } from "./SpeakerProfilesPage";

vi.mock("@/shared/api/endpoints", () => ({
  listSpeakerProfiles: vi.fn(async () => [
    {
      speakerProfileId: "sp1",
      personId: "p1",
      displayName: "李四",
      status: "ACTIVE",
      enrollmentCount: 2,
      lastEnrolledAt: "2026-06-02T00:00:00Z",
      revokedAt: null,
      createdAt: "2026-06-01T00:00:00Z",
      updatedAt: "2026-06-02T00:00:00Z",
    },
  ]),
  revokeSpeakerProfile: vi.fn(async () => undefined),
}));

describe("SpeakerProfilesPage", () => {
  beforeEach(() => vi.clearAllMocks());

  it("lists Java-owned speaker profiles and links back to enrollment", async () => {
    renderPage();

    expect(await screen.findByRole("heading", { name: "声纹档案" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "去录入" })).toHaveAttribute("href", "/enrollment");
    expect(await screen.findByText("李四")).toBeInTheDocument();
    expect(screen.getByText("p1")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("声纹向量已用 KMS 信封加密存储")).toBeInTheDocument();
  });

  it("filters speaker profiles by personId from the URL", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    renderPage("/speaker-profiles?personId=p1");

    await waitFor(() => expect(endpoints.listSpeakerProfiles).toHaveBeenCalledWith("p1"));
    expect(screen.getByRole("link", { name: "去录入" })).toHaveAttribute("href", "/enrollment?personId=p1");
  });

  it("requires confirmation before revoking a speaker profile", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "撤销 李四" }));
    expect(screen.getByRole("dialog", { name: "撤销声纹档案" })).toBeInTheDocument();
    expect(screen.getByText(/撤销后该档案将不再参与后续说话人匹配/)).toBeInTheDocument();
    expect(endpoints.revokeSpeakerProfile).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "确认撤销" }));

    await waitFor(() => expect(endpoints.revokeSpeakerProfile).toHaveBeenCalledWith("sp1", "operator_request"));
    await waitFor(() => expect(endpoints.listSpeakerProfiles).toHaveBeenCalledTimes(2));
  });
});

function renderPage(path = "/speaker-profiles") {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <SpeakerProfilesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
