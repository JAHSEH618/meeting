import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { MeetingListPage } from "../MeetingListPage";

describe("MeetingListPage", () => {
  it("loads and renders meetings", async () => {
    render(
      <MemoryRouter>
        <MeetingListPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
    expect(screen.getByText("CREATED")).toBeInTheDocument();
    expect(screen.getAllByText("INTERNAL").length).toBeGreaterThan(0);
  });
});
