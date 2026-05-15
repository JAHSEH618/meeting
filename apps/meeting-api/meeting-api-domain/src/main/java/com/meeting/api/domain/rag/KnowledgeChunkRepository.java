package com.meeting.api.domain.rag;

public interface KnowledgeChunkRepository {
    /**
     * Mark all knowledge chunks for a meeting as STALE. Used when transcript edits invalidate
     * downstream RAG content; the actual rebuild is async and tracked separately.
     */
    int markStaleForMeeting(String tenantId, String meetingId);

    /**
     * Mark all knowledge chunks for a document as STALE. Used when a document is reindexed
     * (re-parsed, re-chunked, re-embedded). Default no-op for in-memory test stubs.
     */
    default int markStaleForDocument(String tenantId, String documentId) {
        return 0;
    }
}
