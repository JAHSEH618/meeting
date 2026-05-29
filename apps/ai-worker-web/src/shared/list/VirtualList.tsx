import { type ReactNode, useEffect, useRef, useState } from "react";

export interface VirtualListProps<T> {
  items: readonly T[];
  rowHeight: number;
  height: number;
  overscan?: number;
  renderRow: (item: T, index: number) => ReactNode;
  keyOf: (item: T, index: number) => string;
  testId?: string;
}

/**
 * Dependency-free fixed-row-height windowing. Renders only the rows currently
 * inside the scroll viewport (plus an overscan buffer) so dense pages can scale
 * to long transcript / candidate / document lists without dragging in a list
 * library (keeps the first-screen JS budget intact).
 */
export function VirtualList<T>(props: VirtualListProps<T>) {
  const { items, rowHeight, height, renderRow, keyOf, testId } = props;
  const overscan = props.overscan ?? 4;
  const [scrollTop, setScrollTop] = useState(0);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setScrollTop(0);
    if (ref.current) ref.current.scrollTop = 0;
  }, [items]);

  const total = items.length;
  const visibleCount = Math.ceil(height / rowHeight);
  const startIdx = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const endIdx = Math.min(total, startIdx + visibleCount + overscan * 2);
  const offsetY = startIdx * rowHeight;
  const slice: { item: T; index: number }[] = [];
  for (let i = startIdx; i < endIdx; i++) {
    const item = items[i];
    if (item === undefined) continue;
    slice.push({ item, index: i });
  }

  return (
    <div
      ref={ref}
      data-testid={testId}
      role="list"
      aria-rowcount={total}
      onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}
      style={{ height, overflowY: "auto", position: "relative" }}
    >
      <div style={{ height: total * rowHeight, position: "relative" }}>
        <div style={{ transform: `translateY(${offsetY}px)` }}>
          {slice.map(({ item, index }) => (
            <div
              key={keyOf(item, index)}
              role="listitem"
              aria-rowindex={index + 1}
              style={{ height: rowHeight }}
            >
              {renderRow(item, index)}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
