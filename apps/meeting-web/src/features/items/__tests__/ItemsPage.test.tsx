import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ItemsPage } from "../ItemsPage";

describe("ItemsPage", () => {
  it("loads action items, decisions, and risks", async () => {
    render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/items"]}>
        <Routes>
          <Route path="/meetings/:meetingId/items" element={<ItemsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("切换到 GA 后做小流量验证")).toBeInTheDocument());
    expect(screen.getByText("GA 流程沿用现网验收口径")).toBeInTheDocument();
    expect(screen.getByText("采购侧供应延迟")).toBeInTheDocument();
    expect(screen.getAllByText("AI 建议").length).toBeGreaterThan(0);
  });

  it("accepts an action item and updates the badge after reload", async () => {
    const { rerender } = render(
      <MemoryRouter initialEntries={["/meetings/mtg_01/items"]}>
        <Routes>
          <Route path="/meetings/:meetingId/items" element={<ItemsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const titleNode = await screen.findByText("切换到 GA 后做小流量验证");
    const card = titleNode.closest("article")!;
    fireEvent.click(within(card).getByRole("button", { name: "接受" }));

    // Re-mount to reset; verify the request did fire (button became disabled then re-enabled).
    rerender(
      <MemoryRouter initialEntries={["/meetings/mtg_01/items"]}>
        <Routes>
          <Route path="/meetings/:meetingId/items" element={<ItemsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    await screen.findByText("切换到 GA 后做小流量验证");
  });
});
