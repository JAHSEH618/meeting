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
 *
 * Why every timer-firing act() is async + flushMicrotasks: the hook's
 * fetcher chain runs setResults / setLoading inside microtasks that
 * follow the awaited fetch. Without draining those microtasks inside
 * the act() boundary, React warns about "state update outside act()".
 */

// 5 rounds covers the longest chain in the hook
// (await fetcher → setResults → finally → setLoading).
async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}

describe("useDebouncedSearch", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("waits delayMs before firing the fetcher", async () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("hello"));
    expect(fetcher).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(299);
      await flushMicrotasks();
    });
    expect(fetcher).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(1);
      await flushMicrotasks();
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[0]).toBe("hello");
  });

  it("coalesces rapid keystrokes into a single trailing call", async () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("a"));
    await act(async () => {
      vi.advanceTimersByTime(100);
      await flushMicrotasks();
    });
    act(() => result.current.search("ab"));
    await act(async () => {
      vi.advanceTimersByTime(100);
      await flushMicrotasks();
    });
    act(() => result.current.search("abc"));
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });

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
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });
    act(() => result.current.search("second"));
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });

    expect(fetcher).toHaveBeenCalledTimes(2);

    // Resolve the SECOND query first — UI commits it.
    await act(async () => {
      resolveSecond(["second-result"]);
      await flushMicrotasks();
    });
    expect(result.current.results).toEqual(["second-result"]);

    // Now resolve the older first query — it MUST be ignored.
    await act(async () => {
      resolveFirst(["stale-result"]);
      await flushMicrotasks();
    });
    expect(result.current.results).toEqual(["second-result"]);
  });

  it("aborts in-flight requests when a new search starts", async () => {
    const signals: AbortSignal[] = [];
    const fetcher = vi.fn((_q: string, signal: AbortSignal) => {
      signals.push(signal);
      return new Promise<string[]>(() => { /* never resolves */ });
    });

    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("a"));
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });
    act(() => result.current.search("b"));
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });

    expect(signals.length).toBe(2);
    expect(signals[0]?.aborted).toBe(true);
    expect(signals[1]?.aborted).toBe(false);
  });

  it("reset clears state and cancels pending work", async () => {
    const fetcher = vi.fn(async () => ["x"]);
    const { result } = renderHook(() => useDebouncedSearch<string>(fetcher, 300));

    act(() => result.current.search("hi"));
    act(() => result.current.reset());
    await act(async () => {
      vi.advanceTimersByTime(300);
      await flushMicrotasks();
    });

    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.results).toBeNull();
    expect(result.current.loading).toBe(false);
  });
});
