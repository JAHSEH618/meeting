package com.meeting.api.client.rag;

import java.util.List;

/**
 * Caller-supplied scope for a RAG query. Both lists may be empty
 * (meaning "search everything the user can read"); either may be
 * populated to narrow retrieval to specific meetings or documents.
 *
 * <p>This is the client-side mirror of
 * {@code KnowledgeChunkRepository.RetrievalScope}; we keep it separate
 * so that {@code meeting-api-client} stays free of any domain-layer
 * dependency.
 */
public record RagQueryScope(List<String> meetingIds, List<String> documentIds) {

    public static final RagQueryScope EMPTY = new RagQueryScope(List.of(), List.of());

    public RagQueryScope {
        meetingIds = meetingIds == null ? List.of() : List.copyOf(meetingIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public boolean isEmpty() {
        return meetingIds.isEmpty() && documentIds.isEmpty();
    }
}
