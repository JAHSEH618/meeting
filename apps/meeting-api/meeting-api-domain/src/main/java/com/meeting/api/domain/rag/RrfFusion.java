package com.meeting.api.domain.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion — combines the per-channel ranked lists from
 * {@link KnowledgeChunkRepository#searchByVector} and
 * {@link KnowledgeChunkRepository#searchByKeyword} into a single
 * channel-agnostic ranking.
 *
 * <p>For each candidate {@code d} appearing in any list, the fused score is
 * <pre>
 *   rrf(d) = Σ  1 / (k + rank_i(d))
 *           channels
 * </pre>
 * where {@code rank_i(d)} is 1-indexed rank within channel {@code i} and
 * {@code k} is the conventional 60. Candidates absent from a channel
 * contribute nothing for that channel (no penalty).
 *
 * <p>The fused score replaces the per-channel score on the returned
 * {@link KnowledgeChunkCandidate}; metadata (chunkId, content, …) is
 * copied from whichever channel surfaced the chunk first (vector wins
 * ties because dense retrieval typically carries more authoritative embeddings).
 */
public final class RrfFusion {

    /** Standard RRF constant — empirically robust across IR setups. */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {}

    public static List<KnowledgeChunkCandidate> fuse(
        List<KnowledgeChunkCandidate> vectorRanked,
        List<KnowledgeChunkCandidate> keywordRanked
    ) {
        return fuse(vectorRanked, keywordRanked, DEFAULT_K);
    }

    public static List<KnowledgeChunkCandidate> fuse(
        List<KnowledgeChunkCandidate> vectorRanked,
        List<KnowledgeChunkCandidate> keywordRanked,
        int k
    ) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be positive: " + k);
        }
        if (vectorRanked == null) vectorRanked = List.of();
        if (keywordRanked == null) keywordRanked = List.of();

        Map<String, KnowledgeChunkCandidate> firstSeen = new HashMap<>();
        Map<String, Double> rrfScore = new HashMap<>();

        accumulate(vectorRanked, firstSeen, rrfScore, k);
        accumulate(keywordRanked, firstSeen, rrfScore, k);

        List<KnowledgeChunkCandidate> fused = new ArrayList<>(firstSeen.size());
        for (var entry : rrfScore.entrySet()) {
            KnowledgeChunkCandidate base = firstSeen.get(entry.getKey());
            fused.add(new KnowledgeChunkCandidate(
                base.chunkId(),
                base.tenantId(),
                base.projectId(),
                base.meetingId(),
                base.documentId(),
                base.sourceType(),
                base.sourceId(),
                base.sourceSegmentId(),
                base.content(),
                base.transcriptVersion(),
                base.minutesVersion(),
                entry.getValue()
            ));
        }
        // Deterministic ordering: score desc, then chunkId asc so ties are stable.
        fused.sort(
            Comparator.<KnowledgeChunkCandidate>comparingDouble(KnowledgeChunkCandidate::score).reversed()
                .thenComparing(KnowledgeChunkCandidate::chunkId)
        );
        return fused;
    }

    private static void accumulate(
        List<KnowledgeChunkCandidate> ranked,
        Map<String, KnowledgeChunkCandidate> firstSeen,
        Map<String, Double> rrfScore,
        int k
    ) {
        int rank = 1;
        for (KnowledgeChunkCandidate c : ranked) {
            firstSeen.putIfAbsent(c.chunkId(), c);
            rrfScore.merge(c.chunkId(), 1.0 / (k + rank), Double::sum);
            rank++;
        }
    }
}
