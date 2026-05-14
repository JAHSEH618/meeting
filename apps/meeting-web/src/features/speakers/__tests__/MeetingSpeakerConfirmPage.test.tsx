import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { MeetingSpeakerConfirmPage } from "../MeetingSpeakerConfirmPage";

describe("MeetingSpeakerConfirmPage", () => {
  it("loads candidates and renders a confirm button for each authorized person", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/speakers"]}>
        <Routes>
          <Route path="/meetings/:meetingId/speakers" element={<MeetingSpeakerConfirmPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("SPEAKER_00")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /确认为 Alice 张/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "拒绝候选" })).toBeInTheDocument();
    expect(screen.getByText(/自动匹配 78%/)).toBeInTheDocument();
  });

  it("confirms the candidate and triggers a reload", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/speakers"]}>
        <Routes>
          <Route path="/meetings/:meetingId/speakers" element={<MeetingSpeakerConfirmPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const confirmButton = await screen.findByRole("button", { name: /确认为 Alice 张/ });
    fireEvent.click(confirmButton);

    await waitFor(() => expect(screen.getByText("SPEAKER_00")).toBeInTheDocument());
  });
});
