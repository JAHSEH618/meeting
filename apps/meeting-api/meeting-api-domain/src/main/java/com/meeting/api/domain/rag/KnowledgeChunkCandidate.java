package com.meeting.api.domain.rag;

import com.meeting.api.client.enums.SecurityLevel;

/**
 * Lightweight projection of a {@link KnowledgeChunk} surfaced by retrieval
 * (vector / keyword) before reranking and authorization filtering.
 *
 * <p>Carries just enough metadata for {@code RrfFusion} and
 * {@code RagAuthorizationService} to operate without re-hitting the
 * database. {@code score} is the source-specific score (cosine distance
 * inverted to similarity for vector, ts_rank for keyword); fusion is
 * downstream of this record.
 */
public record KnowledgeChunkCandidate(
    String chunkId,
    String tenantId,
    String projectId,
    String meetingId,
    String documentId,
    KnowledgeSourceType sourceType,
    String sourceId,
    String sourceSegmentId,
    String content,
    SecurityLevel securityLevel,
    Integer transcriptVersion,
    Integer minutesVersion,
    double score
) {
}
