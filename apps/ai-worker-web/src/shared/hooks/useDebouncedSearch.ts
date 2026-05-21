import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError } from "@/shared/api/client";

/**
 * Debounce + abortable + last-write-wins search helper.
 *
 * Why a custom hook instead of TanStack Query: the search inputs in this
 * codebase fire on every keystroke. Without debouncing the dev-tools
 * waterfall fills up; without abort + sequence numbers, a slow response
 * for "A" can overwrite the result of "ABC" and the user sees the older
 * list snap back. Both behaviours are flagged in the Web Interface
 * Guidelines (debounce inputs / cancel stale requests).
 *
 * @param fetcher - resolves to results; should pass ``signal`` down to fetch.
 * @param delayMs - quiet period after the last keystroke (default 300ms).
 */
export interface UseDebouncedSearchResult<T> {
  results: T[] | null;
  loading: boolean;
  error: unknown;
  search: (q: string) => void;
  reset: () => void;
}

export function useDebouncedSearch<T>(
  fetcher: (q: string, signal: AbortSignal) => Promise<T[]>,
  delayMs: number = 300,
): UseDebouncedSearchResult<T> {
  const [results, setResults] = useState<T[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // Each pending fetch carries a monotonically-increasing sequence number;
  // we only commit the response when the seq still matches the latest one
  // when the response lands. This protects against the user typing faster
  // than the network — the late "A" reply never overrides the "ABC" result.
  const seqRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Cleanup on unmount so a navigating-away user doesn't see a state update
  // attempted on an unmounted component.
  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      if (abortRef.current) abortRef.current.abort();
    };
  }, []);

  const search = useCallback(
    (q: string) => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      if (abortRef.current) abortRef.current.abort();

      const seq = ++seqRef.current;
      const ac = new AbortController();
      abortRef.current = ac;
      setLoading(true);

      timeoutRef.current = setTimeout(async () => {
        try {
          const r = await fetcher(q, ac.signal);
          if (seq !== seqRef.current) return;
          setResults(r);
          setError(null);
        } catch (e) {
          if (seq !== seqRef.current) return;
          // AbortError is the normal cancel path — don't surface it.
          if (e instanceof DOMException && e.name === "AbortError") return;
          if (e instanceof ApiError && e.error.code === "ABORTED") return;
          setError(e);
        } finally {
          if (seq === seqRef.current) setLoading(false);
        }
      }, delayMs);
    },
    [fetcher, delayMs],
  );

  const reset = useCallback(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    if (abortRef.current) abortRef.current.abort();
    seqRef.current++;
    setResults(null);
    setError(null);
    setLoading(false);
  }, []);

  return { results, loading, error, search, reset };
}
