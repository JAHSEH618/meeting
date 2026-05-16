package com.meeting.api.client.rag;

import java.util.List;

/**
 * Synchronous response from a RAG reindex call.
 *
 * @param staleCount   number of previously-active chunks marked STALE for the owner
 * @param newChunkIds  IDs of freshly-created chunks awaiting their embedding
 */
public record RagReindexResultDTO(int staleCount, List<String> newChunkIds) {
    public RagReindexResultDTO {
        if (newChunkIds == null) {
            newChunkIds = List.of();
        } else {
            newChunkIds = List.copyOf(newChunkIds);
        }
    }
}
