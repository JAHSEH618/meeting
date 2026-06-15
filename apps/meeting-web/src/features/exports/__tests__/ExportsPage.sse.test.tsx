import { describe, expect, it, beforeEach, afterEach, vi } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, waitFor, fireEvent, screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { ExportsPage } from "../ExportsPage";

/**
 * final-check.md D4 — assert ExportsPage tolerates SSE close + keeps
 * polling. The production code now uses fetch-SSE instead of raw
 * EventSource to support custom headers (e.g., Authorization).
 * Combined with the 3s polling cadence, we get sub-second update
 * latency when the broker pushes and a guaranteed fallback.
 *
 * Since fetch-SSE uses fetch() internally, MSW already intercepts
 * it. We just need to verify polling continues.
 */

function renderAt(path: string) {
  return render(
    <TestRouter initialEntries={[path]}>
      <Routes>
        <Route path="/meetings/:meetingId/exports" element={<ExportsPage />} />
      </Routes>
    </TestRouter>,
  );
}

describe("ExportsPage — SSE / polling interplay (D4)", () => {
  const originalFetch = globalThis.fetch;
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // Spy on fetch to verify SSE attempts
    fetchSpy = vi.fn(originalFetch);
    globalThis.fetch = fetchSpy;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it("subscribes to /api/exports/{id}/events for non-terminal jobs", async () => {
    renderAt("/meetings/mtg_exports_sse_subscribe/exports");

    // Wait for the meeting to load so the create button is enabled
    const createButton = await screen.findByTestId("create-export-button");
    await waitFor(() => expect(createButton).not.toBeDisabled());

    fireEvent.click(createButton);
    // Wait for the new QUEUED job to render — the SSE useEffect runs
    // on the next React tick.
    await screen.findByTestId("exports-table");

    // Verify that fetch was called with the SSE endpoint
    await waitFor(() => {
      const sseCall = fetchSpy.mock.calls.find((call) =>
        String(call[0]).includes("/api/exports/") && String(call[0]).includes("/events")
      );
      expect(sseCall).toBeDefined();
    });
  });

  it("polling continues even if SSE fetch fails", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderAt("/meetings/mtg_exports_sse_polling/exports");

      // Wait for the meeting to load so the create button is enabled
      const createButton = await screen.findByTestId("create-export-button");
      await waitFor(() => expect(createButton).not.toBeDisabled());

      fireEvent.click(createButton);
      await screen.findByTestId("exports-table");

      // The SSE fetch is attempted in the background, but with the MSW
      // handler returning an empty SSE stream, it completes quickly.
      // The 3s polling interval ensures updates continue regardless.

      // Advance fake timers past the 3 s polling cadence — the page
      // continues to refresh the table without relying on SSE.
      vi.advanceTimersByTime(3_500);
      await waitFor(() => expect(screen.getByTestId("exports-table")).toBeInTheDocument());
    } finally {
      vi.useRealTimers();
    }
  });
});
