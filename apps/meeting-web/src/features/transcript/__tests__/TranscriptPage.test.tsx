import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { TranscriptPage } from "../TranscriptPage";

describe("TranscriptPage", () => {
  it("renders transcript segments and task link", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/transcript"]}>
        <Routes>
          <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("今天先确认阶段二验收范围。")).toBeInTheDocument());
    expect(screen.getByText("SPEAKER_00")).toBeInTheDocument();
    expect(screen.getByText("任务进度")).toBeInTheDocument();
  });
});
