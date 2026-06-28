package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.rag.ChunkingApplicationService;
import com.meeting.api.app.rag.TranscriptIndexFallbackEvent;
import com.meeting.api.app.rag.TranscriptIndexFallbackRagIndexer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PY-1 — when minutes generation fails, the transcript is not indexed via the
 * minutes path, so {@link TranscriptIndexFallbackRagIndexer} rebuilds the meeting
 * directly. Failures are best-effort (swallowed) because the owning task already
 * failed and a manual reindex remains available.
 */
class TranscriptIndexFallbackRagIndexerTest {

    @Test
    void rebuildsMeetingOnFallbackEvent() {
        ChunkingApplicationService chunker = mock(ChunkingApplicationService.class);
        when(chunker.rebuildForMeeting(eq("tenant_01"), eq("meeting_01")))
            .thenReturn(new ChunkingApplicationService.ChunkingResult(0, List.of("k_a", "k_b")));
        TranscriptIndexFallbackRagIndexer indexer =
            new TranscriptIndexFallbackRagIndexer(chunker, TenantScopedTransaction.immediate(), null);

        indexer.onTranscriptIndexFallback(new TranscriptIndexFallbackEvent("tenant_01", "meeting_01"));

        verify(chunker).rebuildForMeeting(eq("tenant_01"), eq("meeting_01"));
    }

    @Test
    void swallowsChunkerFailureBestEffort() {
        ChunkingApplicationService chunker = mock(ChunkingApplicationService.class);
        when(chunker.rebuildForMeeting(eq("tenant_01"), eq("meeting_01")))
            .thenThrow(new RuntimeException("chunker down"));
        TranscriptIndexFallbackRagIndexer indexer =
            new TranscriptIndexFallbackRagIndexer(chunker, TenantScopedTransaction.immediate(), null);

        // Must NOT rethrow — the task already failed; this is best-effort recovery.
        assertThatCode(() -> indexer.onTranscriptIndexFallback(
            new TranscriptIndexFallbackEvent("tenant_01", "meeting_01")))
            .doesNotThrowAnyException();
        verify(chunker).rebuildForMeeting(eq("tenant_01"), eq("meeting_01"));
    }
}
