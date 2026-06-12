import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor } from "@testing-library/react";
import { MeetingListPage } from "../MeetingListPage";

describe("MeetingListPage", () => {
  it("loads and renders meetings", async () => {
    render(
      <TestRouter>
        <MeetingListPage />
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
    expect(screen.getByText("CREATED")).toBeInTheDocument();
  });
});
