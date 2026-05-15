package com.meeting.api.domain.rag;

import com.meeting.api.client.enums.StaleStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence port for {@link KnowledgeChunk}.
 *
 * <p>Method shapes are kept narrow so the JDBC implementation can take
 * advantage of pgvector HNSW + tsvector GIN indexes without leaking
 * SQL-specific types into the application layer.
 *
 * <p>Most methods are {@code default}-empty so test fakes can implement
 * only the slice they need; production code must rely on the real
 * implementation in {@code JdbcKnowledgeChunkRepository}.
 */
public interface KnowledgeChunkRepository {

    /** Insert or replace a batch of chunks; idempotent on (id) primary key. */
    default void saveAll(Collection<KnowledgeChunk> chunks) {
    }

    /** Return all chunks (active + stale) attached to a meeting. */
    default List<KnowledgeChunk> findByMeetingId(String tenantId, String meetingId) {
        return List.of();
    }

    /** Return all chunks attached to a document. */
    default List<KnowledgeChunk> findByDocumentId(String tenantId, String documentId) {
        return List.of();
    }

    /**
     * Attach the embedding produced by ai-worker. {@code modelVersion} is
     * the runtime-reported version (e.g. {@code bge-m3-v1}), not the
     * caller-advertised one. Writes a defensive copy of the values.
     *
     * @return number of rows touched (0 if the chunk was deleted between
     *     dispatch and callback).
     */
    default int markEmbedding(String tenantId, String chunkId, float[] values, String modelVersion) {
        return 0;
    }

    /**
     * Bulk variant of {@link #markEmbedding} for the TEXT_EMBEDDING
     * callback path. Same return semantics: rows successfully updated.
     */
    default int markEmbeddings(String tenantId, Map<String, EmbeddingResult> embeddingsByChunkId) {
        return 0;
    }

    record EmbeddingResult(float[] values, String modelVersion) {
        public EmbeddingResult {
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("EmbeddingResult.values must be non-empty");
            }
            if (modelVersion == null || modelVersion.isBlank()) {
                throw new IllegalArgumentException("EmbeddingResult.modelVersion must be non-blank");
            }
            values = values.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }
    }

    /**
     * Approximate nearest-neighbor retrieval using pgvector cosine on the
     * HNSW index. Only ACTIVE + ACTIVE chunks are returned; scope filters
     * by meetingIds / documentIds if either list is non-empty.
     *
     * @param queryVector dense embedding of the user query (1024-d for bge-m3).
     */
    default List<KnowledgeChunkCandidate> searchByVector(
        String tenantId,
        float[] queryVector,
        RetrievalScope scope,
        int topK
    ) {
        return List.of();
    }

    /**
     * Keyword retrieval using to_tsvector / plainto_tsquery on the
     * Postgres GIN index. Same status / scope rules as
     * {@link #searchByVector}.
     */
    default List<KnowledgeChunkCandidate> searchByKeyword(
        String tenantId,
        String queryText,
        RetrievalScope scope,
        int topK
    ) {
        return List.of();
    }

    /**
     * Mark all chunks for a meeting as STALE. Used when a transcript edit
     * invalidates downstream RAG content; the actual rebuild is async and
     * tracked separately.
     *
     * @return number of rows touched.
     */
    int markStaleForMeeting(String tenantId, String meetingId);

    /**
     * Mark all chunks for a document as STALE. Used when a document is
     * reindexed (re-parsed, re-chunked, re-embedded).
     */
    default int markStaleForDocument(String tenantId, String documentId) {
        return 0;
    }

    /**
     * Generic stale-state transition for a specific subset of chunks.
     * Returns the number of rows that moved into {@code newStatus}.
     */
    default int updateStaleStatus(String tenantId, Collection<String> chunkIds, StaleStatus newStatus) {
        return 0;
    }

    /**
     * Filter for in-scope retrieval. Empty {@code meetingIds} +
     * empty {@code documentIds} = "anything the user owns" (the second-pass
     * authorization check enforces visibility downstream).
     */
    record RetrievalScope(List<String> meetingIds, List<String> documentIds) {
        public static final RetrievalScope EMPTY = new RetrievalScope(List.of(), List.of());

        public RetrievalScope {
            if (meetingIds == null) meetingIds = List.of();
            if (documentIds == null) documentIds = List.of();
        }

        public boolean isEmpty() {
            return meetingIds.isEmpty() && documentIds.isEmpty();
        }
    }
}
