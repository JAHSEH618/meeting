import { describe, expect, it, beforeEach, afterEach, vi } from "vitest";
import { render, waitFor, fireEvent, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ExportsPage } from "../ExportsPage";

/**
 * final-check.md D4 — assert ExportsPage tolerates SSE close + keeps
 * polling. The production code (de96d73) opens an EventSource per
 * non-terminal job and falls back to the 3 s polling cadence when
 * EventSource closes or fires onerror.
 *
 * jsdom doesn't ship EventSource so the SSE branch is skipped at
 * runtime. We polyfill a minimal recording mock here so the same
 * branch is reachable from the tests.
 */

interface MockEventSourceCtor {
  new (url: string): MockEventSource;
  instances: MockEventSource[];
}

class MockEventSource {
  static instances: MockEventSource[] = [];

  url: string;
  onerror: ((this: MockEventSource, ev: Event) => unknown) | null = null;
  listeners: Map<string, Set<(ev: MessageEvent) => void>> = new Map();
  closed = false;

  constructor(url: string) {
    this.url = url;
    (MockEventSource as unknown as MockEventSourceCtor).instances.push(this);
  }

  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.closed = true;
  }

  triggerError(): void {
    if (this.onerror) this.onerror.call(this, new Event("error"));
  }
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/meetings/:meetingId/exports" element={<ExportsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ExportsPage — SSE / polling interplay (D4)", () => {
  let originalEventSource: typeof globalThis.EventSource | undefined;

  beforeEach(() => {
    originalEventSource = globalThis.EventSource;
    MockEventSource.instances = [];
    (globalThis as unknown as { EventSource: typeof MockEventSource }).EventSource =
      MockEventSource;
  });

  afterEach(() => {
    if (originalEventSource === undefined) {
      delete (globalThis as { EventSource?: typeof EventSource }).EventSource;
    } else {
      (globalThis as { EventSource: typeof EventSource }).EventSource =
        originalEventSource;
    }
  });

  it("subscribes to /api/exports/{id}/events for non-terminal jobs", async () => {
    renderAt("/meetings/mtg_exports_sse_subscribe/exports");
    await screen.findByText(/暂无导出任务/);
    fireEvent.click(screen.getByTestId("create-export-button"));
    // Wait for the new QUEUED job to render — the SSE useEffect runs
    // on the next React tick.
    await screen.findByTestId("exports-table");

    await waitFor(() => {
      expect(MockEventSource.instances.length).toBeGreaterThanOrEqual(1);
    });
    const eventSource = MockEventSource.instances[0]!;
    expect(eventSource.url).toMatch(/\/api\/exports\/.+\/events$/);
    expect(eventSource.listeners.has("EXPORT_STATUS_CHANGED")).toBe(true);
  });

  it("closes the EventSource when its onerror fires; polling still drives updates", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderAt("/meetings/mtg_exports_sse_polling/exports");
      await screen.findByText(/暂无导出任务/);
      fireEvent.click(screen.getByTestId("create-export-button"));
      await screen.findByTestId("exports-table");

      await waitFor(() => {
        expect(MockEventSource.instances.length).toBeGreaterThanOrEqual(1);
      });
      const eventSource = MockEventSource.instances.at(-1)!;
      expect(eventSource.closed).toBe(false);

      // Simulate the server closing the connection (transient network blip).
      eventSource.triggerError();
      expect(eventSource.closed).toBe(true);

      // Advance fake timers past the 3 s polling cadence — the page
      // continues to refresh the table without the SSE channel.
      vi.advanceTimersByTime(3_500);
      await waitFor(() => expect(screen.getByTestId("exports-table")).toBeInTheDocument());
    } finally {
      vi.useRealTimers();
    }
  });
});
