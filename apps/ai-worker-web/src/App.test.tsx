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
});
