package com.meeting.api.app.rag;

/**
 * Published when the Java SUMMARY (minutes) step fails for a MEETING_FULL_PIPELINE
 * task.
 *
 * <p>In the happy path the transcript is indexed into RAG as a side effect of
 * minutes generation: {@code MinutesGeneratedEvent} →
 * {@link MinutesGeneratedRagIndexer} → {@link ChunkingApplicationService#rebuildForMeeting}
 * which chunks {@code PRIMARY_TRANSCRIPT} segments. When minutes generation fails
 * that chain never fires, so an already-persisted transcript would be left
 * unindexed. This event lets {@link TranscriptIndexFallbackRagIndexer} index the
 * transcript directly on a best-effort basis.</p>
 */
public record TranscriptIndexFallbackEvent(String tenantId, String meetingId) {}
