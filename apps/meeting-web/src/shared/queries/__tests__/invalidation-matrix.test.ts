import { describe, it, expect, vi, beforeEach } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { invalidateAfter, type InvalidationEvent } from "../invalidation-matrix";

describe("invalidation-matrix", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
    vi.spyOn(queryClient, "invalidateQueries");
  });

  it("meeting-created event invalidates meetings list", () => {
    const event: InvalidationEvent = {
      type: "meeting-created",
      meetingId: "mtg_123",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meetings"],
    });
  });

  it("meeting-updated event invalidates meetings list + detail", () => {
    const event: InvalidationEvent = {
      type: "meeting-updated",
      meetingId: "mtg_456",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meetings"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_456"],
    });
  });

  it("transcript-edited event invalidates transcript + minutes", () => {
    const event: InvalidationEvent = {
      type: "transcript-edited",
      meetingId: "mtg_789",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["transcript", "mtg_789"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["minutes", "mtg_789"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_789"],
    });
  });

  it("speaker-confirmed event invalidates speakers list", () => {
    const event: InvalidationEvent = {
      type: "speaker-confirmed",
      meetingId: "mtg_abc",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["speakers", "mtg_abc"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["transcript", "mtg_abc"],
    });
  });

  it("unknown events do nothing", () => {
    const event = {
      type: "unknown-event",
      meetingId: "mtg_xyz",
    } as unknown as InvalidationEvent;

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
  });

  it("meeting-deleted event invalidates meetings list + detail", () => {
    const event: InvalidationEvent = {
      type: "meeting-deleted",
      meetingId: "mtg_del",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meetings"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_del"],
    });
  });

  it("speaker-enrolled event invalidates speaker-profiles", () => {
    const event: InvalidationEvent = {
      type: "speaker-enrolled",
      profileId: "prof_123",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["speaker-profiles"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["speaker-profile", "prof_123"],
    });
  });

  it("minutes-regenerated event invalidates minutes + meeting", () => {
    const event: InvalidationEvent = {
      type: "minutes-regenerated",
      meetingId: "mtg_regen",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["minutes", "mtg_regen"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_regen"],
    });
  });

  it("document-uploaded event invalidates documents + meeting", () => {
    const event: InvalidationEvent = {
      type: "document-uploaded",
      meetingId: "mtg_doc",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["documents", "mtg_doc"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_doc"],
    });
  });

  it("task-completed event invalidates tasks + task + meeting + transcript", () => {
    const event: InvalidationEvent = {
      type: "task-completed",
      meetingId: "mtg_task",
      taskId: "task_123",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["tasks", "mtg_task"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["task", "task_123"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["meeting", "mtg_task"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["transcript", "mtg_task"],
    });
  });

  it("export-generated event invalidates exports + export", () => {
    const event: InvalidationEvent = {
      type: "export-generated",
      meetingId: "mtg_export",
      exportId: "exp_123",
    };

    invalidateAfter(event, queryClient);

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["exports", "mtg_export"],
    });
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["export", "exp_123"],
    });
  });
});
