package com.meeting.api.app.rag;

import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryScope;
import java.util.Optional;

/**
 * Optional answer-cache for {@code POST /api/rag/query}.
 *
 * <p>RAG is expensive — embed + dual retrieval + rerank + LLM is at
 * least one network round-trip per stage. The same question, asked by
 * the same caller against the same scope, can reasonably be served
 * from memory until something invalidates the answer. "Coverage
 * eviction" is the spec's term for that invalidation: any meeting /
 * document whose chunks were cited (the "coverage" of the cached
 * answer) becoming stale removes every cached answer that depended on
 * it.
 *
 * <p>The cache is identity-aware on purpose: tenant + user is part of
 * the key, so chunks visible to user A are never served to user B who
 * shares only the question and scope.
 *
 * <p>Implementations MUST be thread-safe and SHOULD enforce a TTL —
 * the cache is best-effort, not a source of truth, and the
 * second-pass authorization in {@link RagAuthorizationService} stays
 * the actual permission gate even when serving from cache. Misses,
 * expirations, and invalidations are all silent fast paths; the
 * application service falls back to the regular pipeline.
 */
public interface RagAnswerCache {

    /** Look up a previously-cached answer. */
    Optional<RagAnswerDTO> lookup(RagCacheKey key);

    /**
     * Store {@code answer} under {@code key} and index it under each
     * owner referenced in {@code coverage}, so a subsequent
     * {@link #invalidateMeeting} / {@link #invalidateDocument} can find
     * and drop it.
     */
    void store(RagCacheKey key, RagAnswerDTO answer, CacheCoverage coverage);

    /**
     * Drop every cached entry whose {@code coverage} touched the given
     * meeting. Called from the chunk-reindex listener.
     *
     * @return number of entries dropped (useful for metrics).
     */
    int invalidateMeeting(String tenantId, String meetingId);

    /** {@link #invalidateMeeting}, but for documents. */
    int invalidateDocument(String tenantId, String documentId);

    /** Drop every entry for a tenant — used by tenant-purge flows. */
    int invalidateTenant(String tenantId);

    /** Test hook + admin tool: empty the cache. */
    void clear();

    /**
     * Cache key. Identity-bearing fields (tenantId, userId) are part of
     * equality so the cache never returns a B-user answer to user A.
     * {@code scope} is canonicalised inside the record so input order
     * doesn't fragment the cache.
     *
     * <p>TODO I13: Add version fields (ragVersion, chunkStrategyVersion) to prevent
     * serving stale cached answers when source data changes. Currently relies on
     * explicit invalidateMeeting/invalidateDocument calls triggered by chunk rebuild.
     */
    record RagCacheKey(
        String tenantId,
        String userId,
        String question,
        RagQueryScope scope,
        int topN,
        boolean includeStale
    ) {
        public RagCacheKey {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question");
            }
            scope = scope == null ? RagQueryScope.EMPTY : new RagQueryScope(
                scope.meetingIds().stream().sorted().distinct().toList(),
                scope.documentIds().stream().sorted().distinct().toList()
            );
        }
    }

    /**
     * What owners the cached answer actually used as evidence — drives
     * coverage-based invalidation. Empty sets mean "answer was
     * degraded / had no citations": such entries are still cached but
     * only TTL can evict them.
     */
    record CacheCoverage(java.util.Set<String> meetingIds, java.util.Set<String> documentIds) {
        public CacheCoverage {
            meetingIds = meetingIds == null ? java.util.Set.of() : java.util.Set.copyOf(meetingIds);
            documentIds = documentIds == null ? java.util.Set.of() : java.util.Set.copyOf(documentIds);
        }
    }
}
