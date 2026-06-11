package com.meeting.api;

import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import com.meeting.api.domain.rag.RrfFusion;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RrfFusionTest {

    @Test
    void itemPresentInBothChannelsScoresHigherThanOnlyOne() {
        var vec = List.of(candidate("a", 0.9), candidate("b", 0.7));
        var kw = List.of(candidate("b", 1.5), candidate("c", 0.8));

        List<KnowledgeChunkCandidate> fused = RrfFusion.fuse(vec, kw);

        // b appears in both lists (vec rank 2 + kw rank 1) — beats a and c.
        assertThat(fused.get(0).chunkId()).isEqualTo("b");
        assertThat(fused).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void higherRankInSingleChannelStillBeatsLowerRank() {
        var vec = List.of(candidate("a", 0.99), candidate("b", 0.5));
        var kw = List.<KnowledgeChunkCandidate>of();

        List<KnowledgeChunkCandidate> fused = RrfFusion.fuse(vec, kw);
        assertThat(fused).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactly("a", "b");
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void perChannelScoreIsReplacedByRrfScore() {
        var vec = List.of(candidate("a", 999.0));   // huge per-channel score
        var kw = List.<KnowledgeChunkCandidate>of();

        double rrfTopScore = RrfFusion.fuse(vec, kw).get(0).score();
        // RRF score for k=60, rank=1 is 1/61 ≈ 0.01639 — NOT 999.
        assertThat(rrfTopScore).isCloseTo(1.0 / 61.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void tiesBreakByChunkIdAlphabetical() {
        var vec = List.of(candidate("z_chunk", 0.9), candidate("a_chunk", 0.9));
        var kw = List.<KnowledgeChunkCandidate>of();

        List<KnowledgeChunkCandidate> fused = RrfFusion.fuse(vec, kw);
        // Both at rank 1+2 respectively in vec → different RRF scores already.
        // But if same rank position (impossible single-list) test explicit ties:
        var vec2 = List.<KnowledgeChunkCandidate>of();
        var kw2 = List.of(candidate("z_chunk", 0.9));
        var fused2 = RrfFusion.fuse(List.of(candidate("a_chunk", 0.9)), kw2);
        // Both at RRF score 1/61 → ordering by chunkId asc, so a_chunk first.
        assertThat(fused2.get(0).chunkId()).isEqualTo("a_chunk");
        assertThat(fused2.get(1).chunkId()).isEqualTo("z_chunk");

        // For the original lists, just check no duplicates and stable shape.
        assertThat(fused).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactlyInAnyOrder("z_chunk", "a_chunk");
    }

    @Test
    void emptyOrNullInputsReturnEmpty() {
        assertThat(RrfFusion.fuse(null, null)).isEmpty();
        assertThat(RrfFusion.fuse(List.of(), List.of())).isEmpty();
    }

    @Test
    void onlyVectorChannelProducesOrderedResult() {
        var vec = List.of(
            candidate("a", 0.9), candidate("b", 0.8), candidate("c", 0.7)
        );
        List<KnowledgeChunkCandidate> fused = RrfFusion.fuse(vec, List.of());
        assertThat(fused).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactly("a", "b", "c");
    }

    @Test
    void onlyKeywordChannelProducesOrderedResult() {
        var kw = List.of(
            candidate("kw_1", 2.0), candidate("kw_2", 1.0)
        );
        List<KnowledgeChunkCandidate> fused = RrfFusion.fuse(List.of(), kw);
        assertThat(fused).extracting(KnowledgeChunkCandidate::chunkId)
            .containsExactly("kw_1", "kw_2");
    }

    @Test
    void smallerKAmplifiesTopRankSignal() {
        var vec = List.of(candidate("a", 1.0), candidate("b", 0.5));
        var kw = List.<KnowledgeChunkCandidate>of();

        double bigK = RrfFusion.fuse(vec, kw, 60).get(0).score();
        double smallK = RrfFusion.fuse(vec, kw, 1).get(0).score();
        assertThat(smallK).isGreaterThan(bigK);
    }

    @Test
    void invalidKThrows() {
        assertThatThrownBy(() -> RrfFusion.fuse(List.of(), List.of(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k must be positive");
        assertThatThrownBy(() -> RrfFusion.fuse(List.of(), List.of(), -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataIsCarriedFromFirstChannelToFuseTheChunk() {
        var vec = List.of(new KnowledgeChunkCandidate(
            "chunk_meta", "tenant", "project_v", "mtg_v", null,
            KnowledgeSourceType.PRIMARY_TRANSCRIPT, "src_v", "seg_v", "from_vector",
            SecurityLevel.INTERNAL, 3, null, 0.5
        ));
        var kw = List.of(new KnowledgeChunkCandidate(
            "chunk_meta", "tenant", "project_k", "mtg_k", null,
            KnowledgeSourceType.AI_SUMMARY, "src_k", "seg_k", "from_keyword",
            SecurityLevel.CONFIDENTIAL, 9, 1, 0.5
        ));

        KnowledgeChunkCandidate fused = RrfFusion.fuse(vec, kw).get(0);
        // Vector channel processed first, so its projection wins.
        assertThat(fused.projectId()).isEqualTo("project_v");
        assertThat(fused.content()).isEqualTo("from_vector");
        assertThat(fused.transcriptVersion()).isEqualTo(3);
    }

    private static KnowledgeChunkCandidate candidate(String id, double score) {
        return new KnowledgeChunkCandidate(
            id, "tenant_01", null, "mtg_01", null,
            KnowledgeSourceType.PRIMARY_TRANSCRIPT, "src_" + id, null,
            "content " + id, SecurityLevel.INTERNAL, 1, null, score
        );
    }
}
