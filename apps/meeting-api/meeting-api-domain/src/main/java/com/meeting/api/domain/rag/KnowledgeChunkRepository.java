package com.meeting.api.domain.rag;

public interface KnowledgeChunkRepository {
    /**
     * Mark all knowledge chunks for a meeting as STALE. Used when transcript edits invalidate
     * downstream RAG content; the actual rebuild is async and tracked separately.
     */
    int markStaleForMeeting(String tenantId, String meetingId);
}
