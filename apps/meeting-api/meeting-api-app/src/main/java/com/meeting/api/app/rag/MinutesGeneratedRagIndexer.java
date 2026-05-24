package com.meeting.api.app.rag;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.minutes.MinutesGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Workstation D4 — when {@link MinutesGeneratedEvent} fires after the minutes write
 * transaction commits, kick off a meeting-scope rebuild: the chunking service produces
 * MINUTES + extraction chunks and emits {@link KnowledgeChunkReindexRequestedEvent},
 * which {@link EmbeddingTaskDispatcher} fans out into TEXT_EMBEDDING tasks.
 *
 * <p>Listener fires AFTER_COMMIT, so the tenant context from the original write
 * transaction has already been reset. We re-open a tenant-scoped transaction so
 * the chunking repos can read tenant-owned tables under RLS.
 *
 * <p>Any exception bubbles up so the embed-task dispatcher is not invoked on a
 * half-write — operators can re-issue {@code POST /rag/reindex/meetings/{id}}.
 */
@Component
public class MinutesGeneratedRagIndexer {
    private static final Logger log = LoggerFactory.getLogger(MinutesGeneratedRagIndexer.class);

    private final ChunkingApplicationService chunkingApplicationService;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final MeetingApiMetrics metrics;

    public MinutesGeneratedRagIndexer(
        ChunkingApplicationService chunkingApplicationService,
        TenantScopedTransaction tenantScopedTransaction,
        MeetingApiMetrics metrics
    ) {
        this.chunkingApplicationService = chunkingApplicationService;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMinutesGenerated(MinutesGeneratedEvent event) {
        try {
            var result = tenantScopedTransaction.execute(
                event.tenantId(), null, null,
                () -> chunkingApplicationService.rebuildForMeeting(event.tenantId(), event.meetingId())
            );
            log.info(
                "minutes_generated_rag_indexed tenant={} meeting={} minutesVersion={} staleChunks={} newChunks={}",
                event.tenantId(), event.meetingId(), event.minutesVersion(),
                result.staleCount(), result.newChunkIds().size()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "minutes_generated_rag_index_failed tenant={} meeting={} minutesVersion={} reason={}",
                event.tenantId(), event.meetingId(), event.minutesVersion(), ex.getMessage(), ex
            );
            if (metrics != null) {
                metrics.outboxFailedCounter("MinutesGeneratedRagIndexer", "REBUILD_FAILED").increment();
            }
            throw ex;
        }
    }
}
