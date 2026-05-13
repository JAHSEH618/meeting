import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { TaskProgressPage } from "../TaskProgressPage";

describe("TaskProgressPage", () => {
  it("renders task status, phase, and step progress from snapshot", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/tasks/task_01"]}>
        <Routes>
          <Route path="/meetings/:meetingId/tasks/:taskId" element={<TaskProgressPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("WORKER_DAG_RUNNING")).toBeInTheDocument());
    expect(screen.getByText("ASR")).toBeInTheDocument();
    expect(screen.getAllByText("RUNNING").length).toBeGreaterThan(0);
    expect(screen.getByText("50%")).toBeInTheDocument();
  });
});
