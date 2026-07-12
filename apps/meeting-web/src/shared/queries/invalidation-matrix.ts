import type { QueryClient } from "@tanstack/react-query";

/**
 * Invalidation events that trigger cache updates.
 * Each event type maps to specific query keys that need to be invalidated.
 */
export type InvalidationEvent =
  | { type: "meeting-created"; meetingId: string }
  | { type: "meeting-updated"; meetingId: string }
  | { type: "meeting-deleted"; meetingId: string }
  | { type: "transcript-edited"; meetingId: string }
  | { type: "speaker-confirmed"; meetingId: string }
  | { type: "speaker-enrolled"; profileId: string }
  | { type: "minutes-regenerated"; meetingId: string }
  | { type: "document-uploaded"; meetingId: string }
  | { type: "task-completed"; meetingId: string; taskId: string }
  | { type: "export-generated"; meetingId: string; exportId: string };

/**
 * Optional context for invalidation logic.
 * Reserved for future use (e.g., tenant-scoped invalidation).
 */
export interface InvalidationContext {
  tenantId?: string;
}

type QueryKey = (string | number | undefined)[];

/**
 * Maps event types to the query keys that should be invalidated.
 * Each entry is a function that receives the event and returns an array of query keys.
 */
const INVALIDATION_MATRIX: Record<
  InvalidationEvent["type"],
  (event: InvalidationEvent) => QueryKey[]
> = {
  "meeting-created": () => [["meetings"]],

  "meeting-updated": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "meeting-updated" }>;
    return [["meetings"], ["meeting", meetingId]];
  },

  "meeting-deleted": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "meeting-deleted" }>;
    return [["meetings"], ["meeting", meetingId]];
  },

  "transcript-edited": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "transcript-edited" }>;
    return [
      ["transcript", meetingId],
      ["minutes", meetingId],
      ["meeting", meetingId],
    ];
  },

  "speaker-confirmed": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "speaker-confirmed" }>;
    return [
      ["speakers", meetingId],
      ["transcript", meetingId],
    ];
  },

  "speaker-enrolled": (event) => {
    const { profileId } = event as Extract<InvalidationEvent, { type: "speaker-enrolled" }>;
    return [
      ["speaker-profiles"],
      ["speaker-profile", profileId],
    ];
  },

  "minutes-regenerated": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "minutes-regenerated" }>;
    return [
      ["minutes", meetingId],
      ["meeting", meetingId],
    ];
  },

  "document-uploaded": (event) => {
    const { meetingId } = event as Extract<InvalidationEvent, { type: "document-uploaded" }>;
    return [
      ["documents", meetingId],
      ["meeting", meetingId],
    ];
  },

  "task-completed": (event) => {
    const { meetingId, taskId } = event as Extract<
      InvalidationEvent,
      { type: "task-completed" }
    >;
    return [
      ["tasks", meetingId],
      ["task", taskId],
      ["meeting", meetingId],
      ["transcript", meetingId],
    ];
  },

  "export-generated": (event) => {
    const { meetingId, exportId } = event as Extract<
      InvalidationEvent,
      { type: "export-generated" }
    >;
    return [
      ["exports", meetingId],
      ["export", exportId],
    ];
  },
};

/**
 * Centralized cache invalidation function.
 * Given an event, determines which query keys need to be invalidated and
 * triggers invalidation via the provided QueryClient.
 *
 * @param event - The invalidation event
 * @param queryClient - TanStack Query client instance
 * @param context - Optional context for future extensions
 *
 * @example
 * ```ts
 * // After creating a meeting
 * invalidateAfter({ type: "meeting-created", meetingId: "mtg_123" }, queryClient);
 *
 * // After editing a transcript
 * invalidateAfter({ type: "transcript-edited", meetingId: "mtg_456" }, queryClient);
 * ```
 */
export function invalidateAfter(
  event: InvalidationEvent,
  queryClient: QueryClient,
  // Reserved for callers that need to thread extra scope in later.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context?: InvalidationContext,
): void {
  const handler = INVALIDATION_MATRIX[event.type];

  if (!handler) {
    // Unknown event type, do nothing
    return;
  }

  const queryKeys = handler(event);

  for (const queryKey of queryKeys) {
    queryClient.invalidateQueries({ queryKey });
  }
}
