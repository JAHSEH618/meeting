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
    expect(container.querySelector(".meeting-modules")).toBeInTheDocument();
    expect(container.querySelector(".glass-panel--table")).toBeInTheDocument();
    expect(screen.getByText("处理链路")).toBeInTheDocument();
    expect(screen.getByText("知识沉淀")).toBeInTheDocument();
    expect(screen.getByText("合规留痕")).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
    expect(screen.getByText("CREATED")).toBeInTheDocument();
  });
});
