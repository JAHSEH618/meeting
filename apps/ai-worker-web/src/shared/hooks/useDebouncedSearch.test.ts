import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";

/**
 * Contract: debounce + abort + last-write-wins.
 *
 * Why this hook exists (and what each test pins): the workstation has
 * keystroke-driven search inputs. Without debounce the network gets
 * hammered; without abort + sequence guard, a slow response for "A" can
 * land after the user typed "ABC" and the UI snaps back to the older
 * result list. The hook eliminates both classes of bug — tests below
 * cover the two failure modes.
 */

describe("useDebouncedSearch", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("waits delayMs before firing the fetcher", () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("hello"));
    expect(fetcher).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(299);
    });
    expect(fetcher).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[0]).toBe("hello");
  });

  it("coalesces rapid keystrokes into a single trailing call", () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("a"));
    act(() => vi.advanceTimersByTime(100));
    act(() => result.current.search("ab"));
    act(() => vi.advanceTimersByTime(100));
    act(() => result.current.search("abc"));
    act(() => vi.advanceTimersByTime(300));

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[0]).toBe("abc");
  });

  it("ignores late responses from superseded queries (last-write-wins)", async () => {
    // Two queries fired back-to-back: the second resolves first; the first
    // resolves later and must NOT overwrite the second's result.
    let resolveFirst: (v: string[]) => void = () => {};
    let resolveSecond: (v: string[]) => void = () => {};

    const fetcher = vi.fn((q: string) => {
      if (q === "first") return new Promise<string[]>((r) => { resolveFirst = r; });
      return new Promise<string[]>((r) => { resolveSecond = r; });
    });

    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("first"));
    act(() => vi.advanceTimersByTime(300));
    act(() => result.current.search("second"));
    act(() => vi.advanceTimersByTime(300));

    expect(fetcher).toHaveBeenCalledTimes(2);

    // Resolve the SECOND query first — UI commits it.
    await act(async () => {
      resolveSecond(["second-result"]);
      await Promise.resolve();
    });
    expect(result.current.results).toEqual(["second-result"]);

    // Now resolve the older first query — it MUST be ignored.
    await act(async () => {
      resolveFirst(["stale-result"]);
      await Promise.resolve();
    });
    expect(result.current.results).toEqual(["second-result"]);
  });

  it("aborts in-flight requests when a new search starts", () => {
    const signals: AbortSignal[] = [];
    const fetcher = vi.fn((_q: string, signal: AbortSignal) => {
      signals.push(signal);
      return new Promise<string[]>(() => { /* never resolves */ });
    });

    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("a"));
    act(() => vi.advanceTimersByTime(300));
    act(() => result.current.search("b"));
    act(() => vi.advanceTimersByTime(300));

    expect(signals.length).toBe(2);
    expect(signals[0]?.aborted).toBe(true);
    expect(signals[1]?.aborted).toBe(false);
  });

  it("reset clears state and cancels pending work", () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("hi"));
    act(() => result.current.reset());
    act(() => vi.advanceTimersByTime(300));

    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.results).toBeNull();
    expect(result.current.loading).toBe(false);
  });
});
