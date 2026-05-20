package com.meeting.api.app.rag;

import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.minutes.MinutesGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Workstation D4 — when {@link MinutesGeneratedEvent} fires after the minutes write
 * transaction commits, kick off a meeting-scope rebuild: the chunking service produces
 * MINUTES + extraction chunks and emits {@link KnowledgeChunkReindexRequestedEvent},
 * which {@link EmbeddingTaskDispatcher} fans out into TEXT_EMBEDDING tasks.
 *
 * <p>This listener does not catch in any state of its own; any exception bubbles to the
 * indexer's own transaction so the embed-task dispatcher is not invoked on a half-write
 * — operators can re-issue {@code POST /rag/reindex/meetings/{id}} to recover.
 */
@Component
public class MinutesGeneratedRagIndexer {
    private static final Logger log = LoggerFactory.getLogger(MinutesGeneratedRagIndexer.class);

    private final ChunkingApplicationService chunkingApplicationService;
    private final MeetingApiMetrics metrics;

    public MinutesGeneratedRagIndexer(
        ChunkingApplicationService chunkingApplicationService,
        MeetingApiMetrics metrics
    ) {
        this.chunkingApplicationService = chunkingApplicationService;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMinutesGenerated(MinutesGeneratedEvent event) {
        try {
            var result = chunkingApplicationService.rebuildForMeeting(event.tenantId(), event.meetingId());
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
