import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSSE } from "../use-sse";

describe("useSSE", () => {
  let mockEventSource: {
    addEventListener: ReturnType<typeof vi.fn>;
    close: ReturnType<typeof vi.fn>;
    readyState: number;
  };

  beforeEach(() => {
    mockEventSource = {
      addEventListener: vi.fn(),
      close: vi.fn(),
      readyState: 0,
    };

    // Mock EventSource constructor
    vi.stubGlobal("EventSource", vi.fn(() => mockEventSource));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllTimers();
  });

  it("should auto-connect on mount by default", () => {
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage));

    expect(EventSource).toHaveBeenCalledWith("/api/events");
  });

  it("should not auto-connect when autoConnect is false", () => {
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage, { autoConnect: false }));

    expect(EventSource).not.toHaveBeenCalled();
  });

  it("should track lastEventId and append to URL on reconnection", async () => {
    const onMessage = vi.fn();
    const { result } = renderHook(() => useSSE("/api/events", onMessage));

    // Simulate connection open
    const openHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "open"
    )?.[1] as ((event: Event) => void) | undefined;
    openHandler?.(new Event("open"));

    // Simulate message with lastEventId
    const messageHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "message"
    )?.[1] as ((event: MessageEvent) => void) | undefined;

    const messageEvent = new MessageEvent("message", {
      data: '{"test":"data"}',
      lastEventId: "event-123",
    });
    messageHandler?.(messageEvent);

    expect(result.current.getLastEventId()).toBe("event-123");

    // Simulate error to trigger reconnection
    const errorHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "error"
    )?.[1] as ((event: Event) => void) | undefined;

    const initialCallCount = (EventSource as unknown as ReturnType<typeof vi.fn>).mock.calls.length;

    errorHandler?.(new Event("error"));

    // Wait a bit for the reconnection to be scheduled
    await new Promise((resolve) => setTimeout(resolve, 1500));

    // Should reconnect with lastEventId in URL
    await waitFor(() => {
      const calls = (EventSource as unknown as ReturnType<typeof vi.fn>).mock.calls;
      expect(calls.length).toBeGreaterThan(initialCallCount);
      const lastCall = calls[calls.length - 1];
      expect(lastCall?.[0]).toBe("/api/events?lastEventId=event-123");
    });
  });

  it("should use exponential backoff on reconnection failures", async () => {
    vi.useFakeTimers();
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage));

    const errorHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "error"
    )?.[1] as ((event: Event) => void) | undefined;

    // First error: 1s delay
    errorHandler?.(new Event("error"));
    expect(EventSource).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(EventSource).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    expect(EventSource).toHaveBeenCalledTimes(2);

    // Second error: 2s delay
    errorHandler?.(new Event("error"));
    await vi.advanceTimersByTimeAsync(1999);
    expect(EventSource).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(1);
    expect(EventSource).toHaveBeenCalledTimes(3);

    // Third error: 4s delay
    errorHandler?.(new Event("error"));
    await vi.advanceTimersByTimeAsync(3999);
    expect(EventSource).toHaveBeenCalledTimes(3);

    await vi.advanceTimersByTimeAsync(1);
    expect(EventSource).toHaveBeenCalledTimes(4);

    vi.useRealTimers();
  });

  it("should reset backoff delay on successful connection", async () => {
    vi.useFakeTimers();
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage));

    const openHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "open"
    )?.[1] as ((event: Event) => void) | undefined;
    const errorHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "error"
    )?.[1] as ((event: Event) => void) | undefined;

    // First error: 1s delay
    errorHandler?.(new Event("error"));
    await vi.advanceTimersByTimeAsync(1000);
    expect(EventSource).toHaveBeenCalledTimes(2);

    // Successful connection resets delay
    openHandler?.(new Event("open"));

    // Another error should use 1s delay again, not 2s
    errorHandler?.(new Event("error"));
    await vi.advanceTimersByTimeAsync(1000);
    expect(EventSource).toHaveBeenCalledTimes(3);

    vi.useRealTimers();
  });

  it("should cap reconnection delay at 30s", async () => {
    vi.useFakeTimers();
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage));

    const errorHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "error"
    )?.[1] as ((event: Event) => void) | undefined;

    // Trigger many errors to exceed 30s cap
    // 1s, 2s, 4s, 8s, 16s, 32s (capped at 30s)
    for (let i = 0; i < 6; i++) {
      errorHandler?.(new Event("error"));
      const expectedDelay = Math.min(1000 * Math.pow(2, i), 30000);
      await vi.advanceTimersByTimeAsync(expectedDelay);
      expect(EventSource).toHaveBeenCalledTimes(i + 2);
    }

    // Next error should still use 30s delay
    errorHandler?.(new Event("error"));
    await vi.advanceTimersByTimeAsync(29999);
    expect(EventSource).toHaveBeenCalledTimes(7);

    await vi.advanceTimersByTimeAsync(1);
    expect(EventSource).toHaveBeenCalledTimes(8);

    vi.useRealTimers();
  });

  it("should call onMessage with event data", async () => {
    const onMessage = vi.fn();
    renderHook(() => useSSE("/api/events", onMessage));

    const messageHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "message"
    )?.[1] as ((event: MessageEvent) => void) | undefined;

    const messageEvent = new MessageEvent("message", {
      data: '{"type":"test"}',
    });
    messageHandler?.(messageEvent);

    expect(onMessage).toHaveBeenCalledWith(messageEvent);
  });

  it("should listen to custom event types", async () => {
    const onMessage = vi.fn();
    renderHook(() =>
      useSSE("/api/events", onMessage, {
        eventTypes: ["TASK_SNAPSHOT", "TASK_COMPLETED"],
      })
    );

    expect(mockEventSource.addEventListener).toHaveBeenCalledWith(
      "TASK_SNAPSHOT",
      expect.any(Function)
    );
    expect(mockEventSource.addEventListener).toHaveBeenCalledWith(
      "TASK_COMPLETED",
      expect.any(Function)
    );

    const taskHandler = mockEventSource.addEventListener.mock.calls.find(
      ([event]) => event === "TASK_SNAPSHOT"
    )?.[1] as ((event: MessageEvent) => void) | undefined;

    const taskEvent = new MessageEvent("message", {
      data: '{"taskId":"task-1"}',
    });
    taskHandler?.(taskEvent);

    expect(onMessage).toHaveBeenCalledWith(taskEvent);
  });

  it("should cleanup on unmount", () => {
    const onMessage = vi.fn();
    const { unmount } = renderHook(() => useSSE("/api/events", onMessage));

    expect(mockEventSource.close).not.toHaveBeenCalled();

    unmount();

    expect(mockEventSource.close).toHaveBeenCalled();
  });

  it("should allow manual connect and disconnect", async () => {
    const onMessage = vi.fn();
    const { result } = renderHook(() =>
      useSSE("/api/events", onMessage, { autoConnect: false })
    );

    expect(EventSource).not.toHaveBeenCalled();

    // Manual connect
    result.current.connect();
    expect(EventSource).toHaveBeenCalledTimes(1);

    // Manual disconnect
    result.current.disconnect();
    expect(mockEventSource.close).toHaveBeenCalled();
  });
});
