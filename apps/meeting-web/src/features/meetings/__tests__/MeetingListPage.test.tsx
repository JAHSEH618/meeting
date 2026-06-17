import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor } from "@testing-library/react";
import { MeetingListPage } from "../MeetingListPage";

describe("MeetingListPage", () => {
  it("loads and renders meetings", async () => {
    const { container } = render(
      <TestRouter>
        <MeetingListPage />
      </TestRouter>,
    );

    expect(container.querySelector(".page--hero")).toBeInTheDocument();
    expect(container.querySelector(".page-hero")).toBeInTheDocument();
    expect(container.querySelector(".glass-panel--table")).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
    expect(screen.getByText("CREATED")).toBeInTheDocument();
  });
});
