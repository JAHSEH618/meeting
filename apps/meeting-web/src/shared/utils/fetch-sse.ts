/**
 * Fetch-based SSE client for endpoints requiring custom headers (e.g., Authorization).
 *
 * EventSource doesn't support custom headers, so this utility uses fetch + ReadableStream
 * to parse SSE events manually.
 *
 * @example
 * ```tsx
 * const abortController = new AbortController();
 *
 * (async () => {
 *   try {
 *     for await (const event of fetchSSE('/api/exports/123/events', {
 *       signal: abortController.signal,
 *       headers: { Authorization: `Bearer ${token}` },
 *     })) {
 *       const data = JSON.parse(event.data);
 *       console.log('Event:', data);
 *     }
 *   } catch (error) {
 *     if (error.name !== 'AbortError') {
 *       console.error('SSE error:', error);
 *     }
 *   }
 * })();
 *
 * // Cleanup
 * return () => abortController.abort();
 * ```
 */
export async function* fetchSSE(
  url: string,
  options?: { signal?: AbortSignal; headers?: HeadersInit }
): AsyncGenerator<MessageEvent, void, unknown> {
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: "text/event-stream",
      ...options?.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`SSE fetch failed: ${response.status} ${response.statusText}`);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("Response body is not readable");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let eventType = "";
  let eventId = "";
  let eventData = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        // Empty line signals end of event
        if (line === "" || line === "\r") {
          if (eventData) {
            yield new MessageEvent(eventType || "message", {
              data: eventData.endsWith("\n") ? eventData.slice(0, -1) : eventData,
              lastEventId: eventId || undefined,
            });
            eventData = "";
            eventType = "";
            // eventId persists across events until explicitly updated
          }
          continue;
        }

        // Comment line
        if (line.startsWith(":")) {
          continue;
        }

        // Parse field
        const colonIndex = line.indexOf(":");
        if (colonIndex === -1) {
          continue;
        }

        const field = line.slice(0, colonIndex);
        let value = line.slice(colonIndex + 1);

        // Remove leading space after colon (per SSE spec)
        if (value.startsWith(" ")) {
          value = value.slice(1);
        }

        switch (field) {
          case "event":
            eventType = value;
            break;
          case "data":
            eventData += value + "\n";
            break;
          case "id":
            eventId = value;
            break;
          case "retry":
            // Ignore retry field for now
            break;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
