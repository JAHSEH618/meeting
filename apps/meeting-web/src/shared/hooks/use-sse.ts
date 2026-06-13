import { useCallback, useEffect, useRef, useState } from "react";

export interface UseSSEOptions {
  /** Whether to auto-connect on mount (default: true) */
  autoConnect?: boolean;
  /** Custom event types to listen for (beyond 'message') */
  eventTypes?: string[];
  /** Callback when connection opens */
  onOpen?: () => void;
  /** Callback when an error occurs */
  onError?: (error: Event) => void;
}

export interface UseSSEReturn {
  /** Current connection state */
  connected: boolean;
  /** Connect or reconnect to the SSE endpoint */
  connect: () => void;
  /** Close the connection */
  disconnect: () => void;
  /** Get the last received event ID */
  getLastEventId: () => string | null;
}

/**
 * SSE hook with lastEventId tracking and exponential backoff.
 *
 * Features:
 * - Tracks lastEventId for resume-on-reconnect
 * - Exponential backoff: 1s → 2s → 4s → ... → 30s (capped)
 * - Resets backoff on successful connection
 *
 * @example
 * ```tsx
 * const { connected, getLastEventId } = useSSE('/api/events', {
 *   eventTypes: ['TASK_SNAPSHOT', 'TASK_COMPLETED'],
 *   onOpen: () => console.log('Connected'),
 * });
 * ```
 */
export function useSSE(
  url: string,
  onMessage: (event: MessageEvent) => void,
  options: UseSSEOptions = {}
): UseSSEReturn {
  const { autoConnect = true, eventTypes = [], onOpen, onError } = options;

  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const lastEventIdRef = useRef<string | null>(null);
  const reconnectDelayRef = useRef(1000); // Start at 1s
  const reconnectTimeoutRef = useRef<number | null>(null);
  const unmountedRef = useRef(false);

  const MAX_RECONNECT_DELAY = 30000; // Cap at 30s

  const disconnect = useCallback(() => {
    if (reconnectTimeoutRef.current !== null) {
      window.clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
      setConnected(false);
    }
  }, []);

  const connect = useCallback(() => {
    if (unmountedRef.current) return;

    // Close existing connection
    disconnect();

    try {
      // Append lastEventId to URL for resume-on-reconnect
      const connectUrl = lastEventIdRef.current
        ? `${url}${url.includes("?") ? "&" : "?"}lastEventId=${encodeURIComponent(lastEventIdRef.current)}`
        : url;

      const eventSource = new EventSource(connectUrl);
      eventSourceRef.current = eventSource;

      eventSource.addEventListener("open", () => {
        if (unmountedRef.current) return;
        setConnected(true);
        // Reset backoff on successful connection
        reconnectDelayRef.current = 1000;
        onOpen?.();
      });

      // Handle generic 'message' events
      eventSource.addEventListener("message", (event) => {
        if (unmountedRef.current) return;
        if (event.lastEventId) {
          lastEventIdRef.current = event.lastEventId;
        }
        onMessage(event);
      });

      // Handle custom event types
      for (const eventType of eventTypes) {
        eventSource.addEventListener(eventType, (event) => {
          if (unmountedRef.current) return;
          if (event.lastEventId) {
            lastEventIdRef.current = event.lastEventId;
          }
          onMessage(event as MessageEvent);
        });
      }

      eventSource.addEventListener("error", (error) => {
        if (unmountedRef.current) return;
        setConnected(false);
        onError?.(error);

        // Close and schedule reconnect with exponential backoff
        eventSource.close();
        eventSourceRef.current = null;

        const delay = reconnectDelayRef.current;
        reconnectTimeoutRef.current = window.setTimeout(() => {
          if (unmountedRef.current) return;
          connect();
          // Double delay for next time, up to max
          reconnectDelayRef.current = Math.min(
            reconnectDelayRef.current * 2,
            MAX_RECONNECT_DELAY
          );
        }, delay);
      });
    } catch (error) {
      // EventSource construction failure
      console.error("Failed to create EventSource:", error);
      setConnected(false);
    }
  }, [url, onMessage, eventTypes, onOpen, onError, disconnect]);

  const getLastEventId = useCallback(() => lastEventIdRef.current, []);

  // Auto-connect on mount if enabled
  useEffect(() => {
    if (autoConnect) {
      connect();
    }

    return () => {
      unmountedRef.current = true;
      disconnect();
    };
  }, [autoConnect, connect, disconnect]);

  return {
    connected,
    connect,
    disconnect,
    getLastEventId,
  };
}
