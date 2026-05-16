package com.meeting.api.app.rag;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.rag.RagReindexFacade;
import com.meeting.api.client.rag.RagReindexResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Adapter-facing entry point for {@code POST /api/rag/reindex/{meetings|documents}/{id}}.
 *
 * <p>Sets up tenant context + an outer transaction, then delegates to
 * {@link ChunkingApplicationService}. The chunking service is the one
 * that emits {@link KnowledgeChunkReindexRequestedEvent} so the
 * embedding dispatcher can fan the new chunks into TEXT_EMBEDDING tasks
 * after this transaction commits.
 */
@Service
public class RagReindexApplicationService implements RagReindexFacade {

    private static final Logger log = LoggerFactory.getLogger(RagReindexApplicationService.class);

    private final TenantScopedTransaction tenantScopedTransaction;
    private final ChunkingApplicationService chunkingApplicationService;

    public RagReindexApplicationService(
        TenantScopedTransaction tenantScopedTransaction,
        ChunkingApplicationService chunkingApplicationService
    ) {
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.chunkingApplicationService = chunkingApplicationService;
    }

    @Override
    public RagReindexResultDTO reindexMeeting(String tenantId, String meetingId, String requestedBy) {
        return tenantScopedTransaction.execute(tenantId, requestedBy, null, () -> {
            var result = chunkingApplicationService.rebuildForMeeting(tenantId, meetingId);
            log.info(
                "rag_reindex_meeting tenant={} meeting={} stale={} new={} by={}",
                tenantId, meetingId, result.staleCount(), result.newChunkIds().size(), requestedBy
            );
            return new RagReindexResultDTO(result.staleCount(), result.newChunkIds());
        });
    }

    @Override
    public RagReindexResultDTO reindexDocument(String tenantId, String documentId, String requestedBy) {
        return tenantScopedTransaction.execute(tenantId, requestedBy, null, () -> {
            var result = chunkingApplicationService.rebuildForDocument(tenantId, documentId);
            log.info(
                "rag_reindex_document tenant={} doc={} stale={} new={} by={}",
                tenantId, documentId, result.staleCount(), result.newChunkIds().size(), requestedBy
            );
            return new RagReindexResultDTO(result.staleCount(), result.newChunkIds());
        });
    }
}
