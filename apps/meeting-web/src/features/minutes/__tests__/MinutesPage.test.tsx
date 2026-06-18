import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { MinutesPage } from "../MinutesPage";

describe("MinutesPage", () => {
  it("loads minutes and shows sections with evidence", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("阶段二上线")).toBeInTheDocument());
    expect(screen.getByText("引用片段 1")).toBeInTheDocument();
  });

  it("clicks regenerate and replaces minutes with new version", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await screen.findByText("阶段二上线");
    fireEvent.click(screen.getByRole("button", { name: "重新生成纪要" }));

    await waitFor(() => expect(screen.getByText("纪要已重生成")).toBeInTheDocument());
    expect(screen.getByText(/v2/)).toBeInTheDocument();
  });
});
