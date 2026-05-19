package com.meeting.api;

import com.meeting.api.app.rag.ChunkingApplicationService;
import com.meeting.api.app.rag.MinutesGeneratedRagIndexer;
import com.meeting.api.domain.minutes.MinutesGeneratedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workstation D4 — verifies that MinutesGeneratedEvent triggers a meeting rebuild
 * with the chunking service. The chunker itself fans out the resulting chunks to
 * the embed queue (via KnowledgeChunkReindexRequestedEvent → EmbeddingTaskDispatcher),
 * which is covered by ChunkingApplicationServiceTest + EmbeddingTaskDispatcherTest.
 */
class MinutesGeneratedRagIndexerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T05:00:00Z");

    @Test
    void onMinutesGeneratedRebuildsMeetingChunks() {
        ChunkingApplicationService chunker = mock(ChunkingApplicationService.class);
        when(chunker.rebuildForMeeting(eq("tenant_01"), eq("meeting_01")))
            .thenReturn(new ChunkingApplicationService.ChunkingResult(2, List.of("k_a", "k_b", "k_c")));
        MinutesGeneratedRagIndexer indexer = new MinutesGeneratedRagIndexer(chunker, null);

        MinutesGeneratedEvent event = new MinutesGeneratedEvent(
            "evt_1", "tenant_01", "meeting_01", "min_1", 3, 5, 1L, NOW
        );
        indexer.onMinutesGenerated(event);

        ArgumentCaptor<String> meetingCap = ArgumentCaptor.forClass(String.class);
        verify(chunker).rebuildForMeeting(eq("tenant_01"), meetingCap.capture());
        assertThat(meetingCap.getValue()).isEqualTo("meeting_01");
    }

    @Test
    void exceptionsBubbleSoOuterTransactionMayRollBack() {
        ChunkingApplicationService chunker = mock(ChunkingApplicationService.class);
        when(chunker.rebuildForMeeting(eq("tenant_01"), eq("meeting_01")))
            .thenThrow(new RuntimeException("chunker down"));
        MinutesGeneratedRagIndexer indexer = new MinutesGeneratedRagIndexer(chunker, null);

        MinutesGeneratedEvent event = new MinutesGeneratedEvent(
            "evt_2", "tenant_01", "meeting_01", "min_1", 1, 1, 1L, NOW
        );
        assertThatThrownBy(() -> indexer.onMinutesGenerated(event))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("chunker down");
    }

    @Test
    void doesNotInvokeChunkerForUnrelatedEvents() {
        ChunkingApplicationService chunker = mock(ChunkingApplicationService.class);
        MinutesGeneratedRagIndexer indexer = new MinutesGeneratedRagIndexer(chunker, null);

        // No event delivered → no interaction.
        verify(chunker, never()).rebuildForMeeting(eq("tenant_01"), eq("meeting_01"));
    }
}
