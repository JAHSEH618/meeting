package com.meeting.api.client.rag;

/**
 * Re-build the {@code knowledge_chunks} rows for a meeting or document.
 *
 * <p>The implementation marks existing chunks for that owner as
 * {@code STALE}, re-splits the source-of-truth content into new chunks,
 * persists them with {@code embedding=NULL}, and then publishes an event
 * that fans the new chunks into {@code TEXT_EMBEDDING} tasks. The HTTP
 * caller therefore returns synchronously with the count of stale-marked
 * rows and the freshly-created chunk IDs; embedding completion is
 * observed later via the existing processing-task SSE feed.
 */
public interface RagReindexFacade {

    /** Re-chunk a meeting (transcript + minutes + accepted extractions). */
    RagReindexResultDTO reindexMeeting(String tenantId, String meetingId, String requestedBy);

    /** Re-chunk a previously-parsed document. */
    RagReindexResultDTO reindexDocument(String tenantId, String documentId, String requestedBy);
}
