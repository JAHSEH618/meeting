package com.meeting.api.domain.document;

import java.time.OffsetDateTime;
import java.util.List;

public interface DocumentChunkRepository {
    void replaceChunks(String tenantId, String documentId, List<ChunkRecord> chunks, OffsetDateTime now);

    List<ChunkRecord> findByDocument(String tenantId, String documentId);

    record ChunkRecord(
        String id,
        String tenantId,
        String documentId,
        int chunkIndex,
        Integer pageNumber,
        String content,
        String contentHash
    ) {
    }
}
