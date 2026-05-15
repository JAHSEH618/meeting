package com.meeting.api;

import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.domain.rag.ChunkStatus;
import com.meeting.api.domain.rag.ChunkStrategy;
import com.meeting.api.domain.rag.KnowledgeChunk;
import com.meeting.api.domain.rag.KnowledgeSourceType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeChunkDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T08:00:00Z");

    @Test
    void defaultChunkStrategyMatchesBgeM3Sweetspot() {
        ChunkStrategy s = ChunkStrategy.DEFAULT_ZH;
        assertThat(s.name()).isEqualTo("default-zh-v1");
        assertThat(s.maxTokens()).isEqualTo(512);
        assertThat(s.overlapTokens()).isEqualTo(64);
        assertThat(s.tokenizer()).isEqualTo("chinese-char");
    }

    @Test
    void chunkStrategyRejectsOverlapEqualOrGreaterThanMax() {
        assertThatThrownBy(() -> new ChunkStrategy("custom", 200, 200, "tok"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlapTokens=200");
        assertThatThrownBy(() -> new ChunkStrategy("custom", 200, 250, "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkStrategyRejectsOutOfRangeMaxTokens() {
        assertThatThrownBy(() -> new ChunkStrategy("x", 16, 0, "tok"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTokens=16");
        assertThatThrownBy(() -> new ChunkStrategy("x", 5000, 0, "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkStrategyRejectsBlankName() {
        assertThatThrownBy(() -> new ChunkStrategy(" ", 256, 32, "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshChunkStartsActiveAndWithoutEmbedding() {
        KnowledgeChunk chunk = newChunkBuilder().build();
        assertThat(chunk.status()).isEqualTo(ChunkStatus.ACTIVE);
        assertThat(chunk.staleStatus()).isEqualTo(StaleStatus.ACTIVE);
        assertThat(chunk.hasEmbedding()).isFalse();
        assertThat(chunk.embedding()).isNull();
        assertThat(chunk.isActiveAndFresh()).isTrue();
    }

    @Test
    void markEmbeddingStoresDefensiveCopyAndUpdatesVersionAndTimestamp() {
        KnowledgeChunk chunk = newChunkBuilder().createdAt(NOW).build();
        float[] values = {0.1f, 0.2f, 0.3f};

        chunk.markEmbedding(values, "bge-m3-v1", NOW.plusMinutes(1));

        assertThat(chunk.hasEmbedding()).isTrue();
        assertThat(chunk.embeddingModelVersion()).isEqualTo("bge-m3-v1");
        assertThat(chunk.updatedAt()).isEqualTo(NOW.plusMinutes(1));

        // Mutating the caller's array must NOT bleed into the chunk.
        values[0] = 99.0f;
        assertThat(chunk.embedding()[0]).isEqualTo(0.1f);

        // The returned array is also a defensive copy.
        float[] read = chunk.embedding();
        read[1] = 99.0f;
        assertThat(chunk.embedding()[1]).isEqualTo(0.2f);
    }

    @Test
    void markEmbeddingRejectsEmptyValues() {
        KnowledgeChunk chunk = newChunkBuilder().build();
        assertThatThrownBy(() -> chunk.markEmbedding(new float[0], "bge-m3-v1", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markStaleFlipsFreshness() {
        KnowledgeChunk chunk = newChunkBuilder().build();
        chunk.markStale(StaleStatus.STALE);
        assertThat(chunk.staleStatus()).isEqualTo(StaleStatus.STALE);
        assertThat(chunk.isActiveAndFresh()).isFalse();
    }

    @Test
    void markDeletedSetsBothStatusFields() {
        KnowledgeChunk chunk = newChunkBuilder().build();
        chunk.markDeleted();
        assertThat(chunk.status()).isEqualTo(ChunkStatus.DELETED);
        assertThat(chunk.staleStatus()).isEqualTo(StaleStatus.DELETED);
        assertThat(chunk.isActiveAndFresh()).isFalse();
    }

    @Test
    void documentSourcedChunkRequiresDocumentId() {
        assertThatThrownBy(() -> KnowledgeChunk.builder()
            .id("c1")
            .tenantId("t1")
            .sourceType(KnowledgeSourceType.DOCUMENT)
            .sourceId("doc1#0")
            .meetingId("mtg1")  // wrong — should be documentId
            .content("hello world")
            .contentHash("h")
            .chunkStrategyVersion("default-zh-v1")
            .securityLevel(SecurityLevel.INTERNAL)
            .build()
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DOCUMENT-sourced chunk requires documentId");
    }

    @Test
    void transcriptSourcedChunkRequiresMeetingId() {
        assertThatThrownBy(() -> KnowledgeChunk.builder()
            .id("c1")
            .tenantId("t1")
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_1")
            .documentId("doc1")  // wrong — needs meetingId
            .content("hello world")
            .contentHash("h")
            .chunkStrategyVersion("default-zh-v1")
            .securityLevel(SecurityLevel.INTERNAL)
            .build()
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires meetingId");
    }

    @Test
    void blankContentIsRejected() {
        assertThatThrownBy(() -> newChunkBuilder().content("   ").build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeChunk.Builder newChunkBuilder() {
        return KnowledgeChunk.builder()
            .id("chunk_01")
            .tenantId("tenant_01")
            .meetingId("mtg_01")
            .sourceType(KnowledgeSourceType.PRIMARY_TRANSCRIPT)
            .sourceId("seg_1")
            .sourceSegmentId("seg_1")
            .content("This is some transcript content.")
            .contentHash("hash_abc123")
            .chunkStrategyVersion("default-zh-v1")
            .securityLevel(SecurityLevel.INTERNAL);
    }
}
