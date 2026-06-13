import { describe, expect, it, vi } from "vitest";
import { fetchSSE } from "../fetch-sse";

describe("fetchSSE", () => {
  it("should parse SSE events from fetch response", async () => {
    const sseData = [
      "data: first message\n",
      "\n",
      "data: second message\n",
      "\n",
    ].join("");

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(sseData),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(2);
    expect(events[0]?.data).toBe("first message");
    expect(events[1]?.data).toBe("second message");
    expect(mockReader.releaseLock).toHaveBeenCalled();
  });

  it("should parse custom event types", async () => {
    const sseData = [
      "event: TASK_SNAPSHOT\n",
      "data: {\"taskId\":\"task-1\"}\n",
      "\n",
    ].join("");

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(sseData),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(1);
    expect(events[0]?.type).toBe("TASK_SNAPSHOT");
    expect(events[0]?.data).toBe('{"taskId":"task-1"}');
  });

  it("should track lastEventId", async () => {
    const sseData = [
      "id: event-123\n",
      "data: first message\n",
      "\n",
      "id: event-456\n",
      "data: second message\n",
      "\n",
    ].join("");

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(sseData),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(2);
    expect(events[0]?.lastEventId).toBe("event-123");
    expect(events[1]?.lastEventId).toBe("event-456");
  });

  it("should handle multi-line data fields", async () => {
    const sseData = [
      "data: line 1\n",
      "data: line 2\n",
      "data: line 3\n",
      "\n",
    ].join("");

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(sseData),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(1);
    expect(events[0]?.data).toBe("line 1\nline 2\nline 3");
  });

  it("should ignore comment lines", async () => {
    const sseData = [
      ": this is a comment\n",
      "data: actual message\n",
      "\n",
    ].join("");

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(sseData),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(1);
    expect(events[0]?.data).toBe("actual message");
  });

  it("should include custom headers in fetch request", async () => {
    const mockReader = {
      read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events", {
      headers: { Authorization: "Bearer token123" },
    })) {
      events.push(event);
    }

    expect(fetch).toHaveBeenCalledWith("/api/events", {
      headers: {
        Accept: "text/event-stream",
        Authorization: "Bearer token123",
      },
    });
  });

  it("should throw error on non-ok response", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: "Not Found",
    });

    await expect(async () => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      for await (const _event of fetchSSE("/api/events")) {
        // Should not reach here
      }
    }).rejects.toThrow("SSE fetch failed: 404 Not Found");
  });

  it("should throw error when body is not readable", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: null,
    });

    await expect(async () => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      for await (const _event of fetchSSE("/api/events")) {
        // Should not reach here
      }
    }).rejects.toThrow("Response body is not readable");
  });

  it("should handle abort signal", async () => {
    const mockReader = {
      read: vi.fn().mockImplementation(() => {
        throw new DOMException("Aborted", "AbortError");
      }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const abortController = new AbortController();
    abortController.abort();

    await expect(async () => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      for await (const _event of fetchSSE("/api/events", {
        signal: abortController.signal,
      })) {
        // Should not reach here
      }
    }).rejects.toThrow("Aborted");

    expect(mockReader.releaseLock).toHaveBeenCalled();
  });

  it("should handle chunked data across multiple reads", async () => {
    const chunk1 = "data: partial";
    const chunk2 = " message\n\n";

    const mockReader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(chunk1),
        })
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(chunk2),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      releaseLock: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => mockReader,
      },
    });

    const events: MessageEvent[] = [];
    for await (const event of fetchSSE("/api/events")) {
      events.push(event);
    }

    expect(events).toHaveLength(1);
    expect(events[0]?.data).toBe("partial message");
  });
});
