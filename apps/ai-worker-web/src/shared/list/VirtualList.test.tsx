import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { VirtualList } from "./VirtualList";

const makeItems = (n: number) =>
  Array.from({ length: n }, (_, i) => ({ id: `r${i}`, label: `row ${i}` }));

describe("VirtualList", () => {
  it("renders only the rows that fit the viewport plus overscan", () => {
    render(
      <VirtualList
        items={makeItems(1000)}
        rowHeight={20}
        height={100} // 5 rows fit + overscan=4*2 = 13 max
        overscan={4}
        keyOf={(it) => it.id}
        renderRow={(it) => <span>{it.label}</span>}
        testId="vl"
      />,
    );
    expect(screen.queryByText("row 0")).toBeTruthy();
    expect(screen.queryByText("row 999")).toBeNull();
    const items = screen.getAllByRole("listitem");
    expect(items.length).toBeLessThan(30);
  });

  it("updates the visible window when scrolled", () => {
    render(
      <VirtualList
        items={makeItems(500)}
        rowHeight={10}
        height={50}
        overscan={2}
        keyOf={(it) => it.id}
        renderRow={(it) => <span>{it.label}</span>}
        testId="vl-scroll"
      />,
    );
    const container = screen.getByTestId("vl-scroll");
    fireEvent.scroll(container, { target: { scrollTop: 2000 } });
    expect(screen.queryByText("row 200")).toBeTruthy();
    expect(screen.queryByText("row 0")).toBeNull();
  });

  it("exposes total via aria-rowcount", () => {
    render(
      <VirtualList
        items={makeItems(42)}
        rowHeight={20}
        height={100}
        keyOf={(it) => it.id}
        renderRow={(it) => <span>{it.label}</span>}
        testId="vl-aria"
      />,
    );
    expect(screen.getByTestId("vl-aria")).toHaveAttribute("aria-rowcount", "42");
  });

  it("handles empty input without crashing", () => {
    render(
      <VirtualList
        items={[]}
        rowHeight={20}
        height={100}
        keyOf={(it: { id: string }) => it.id}
        renderRow={(it: { id: string }) => <span>{it.id}</span>}
        testId="vl-empty"
      />,
    );
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
  });
});
