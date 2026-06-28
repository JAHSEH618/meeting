package com.meeting.api.app.rag;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.observability.MeetingApiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PY-1 fallback — indexes the transcript into RAG when the minutes path that
 * normally does so never ran (the Java SUMMARY step failed).
 *
 * <p>Unlike {@link MinutesGeneratedRagIndexer}, failures here are logged and
 * swallowed rather than rethrown: the owning task has already reached a failed
 * terminal state, this is a best-effort recovery, and a manual
 * {@code POST /rag/reindex/meetings/{id}} remains available. Listens
 * AFTER_COMMIT with {@code fallbackExecution} so it runs whether or not a
 * transaction is active when the event is published.</p>
 */
@Component
public class TranscriptIndexFallbackRagIndexer {
    private static final Logger log = LoggerFactory.getLogger(TranscriptIndexFallbackRagIndexer.class);

    private final ChunkingApplicationService chunkingApplicationService;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final MeetingApiMetrics metrics;

    public TranscriptIndexFallbackRagIndexer(
        ChunkingApplicationService chunkingApplicationService,
        TenantScopedTransaction tenantScopedTransaction,
        MeetingApiMetrics metrics
    ) {
        this.chunkingApplicationService = chunkingApplicationService;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTranscriptIndexFallback(TranscriptIndexFallbackEvent event) {
        try {
            var result = tenantScopedTransaction.execute(
                event.tenantId(), null, null,
                () -> chunkingApplicationService.rebuildForMeeting(event.tenantId(), event.meetingId())
            );
            log.info(
                "transcript_index_fallback_indexed tenant={} meeting={} staleChunks={} newChunks={}",
                event.tenantId(), event.meetingId(), result.staleCount(), result.newChunkIds().size()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "transcript_index_fallback_failed tenant={} meeting={} reason={}",
                event.tenantId(), event.meetingId(), ex.getMessage(), ex
            );
            if (metrics != null) {
                metrics.outboxFailedCounter("TranscriptIndexFallbackRagIndexer", "REBUILD_FAILED").increment();
            }
        }
    }
}
