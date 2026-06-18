import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { MeetingSpeakerConfirmPage } from "../MeetingSpeakerConfirmPage";

describe("MeetingSpeakerConfirmPage", () => {
  it("loads candidates and renders a confirm button for each authorized person", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/speakers"]}>
        <Routes>
          <Route path="/meetings/:meetingId/speakers" element={<MeetingSpeakerConfirmPage />} />
        </Routes>
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("说话人 1")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /确认为 Alice 张/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "拒绝候选" })).toBeInTheDocument();
    expect(screen.getByText(/匹配 78%/)).toBeInTheDocument();
  });

  it("confirms the candidate and triggers a reload", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/speakers"]}>
        <Routes>
          <Route path="/meetings/:meetingId/speakers" element={<MeetingSpeakerConfirmPage />} />
        </Routes>
      </TestRouter>,
    );

    const confirmButton = await screen.findByRole("button", { name: /确认为 Alice 张/ });
    fireEvent.click(confirmButton);

    await waitFor(() => expect(screen.getByText("说话人 1")).toBeInTheDocument());
  });
});
