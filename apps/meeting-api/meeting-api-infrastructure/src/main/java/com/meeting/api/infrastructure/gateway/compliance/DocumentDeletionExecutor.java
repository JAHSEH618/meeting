package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.document.DocumentRepository.DocumentRecord;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DOCUMENT-scope deletion executor.
 *
 * <p>Soft-deletes the {@code documents} row and stale-cascades the
 * document's {@code knowledge_chunks}. The chunks are marked
 * {@code STALE} (rather than {@code DELETED}) because RAG retrieval
 * already drops STALE chunks; the destructive transition to status
 * {@code DELETED} is left for the chunk-deletion expansion PR.
 */
@Component
public class DocumentDeletionExecutor implements DeletionExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionExecutor.class);

    private final DocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final Clock clock;

    public DocumentDeletionExecutor(
        DocumentRepository documentRepository,
        KnowledgeChunkRepository chunkRepository
    ) {
        this(documentRepository, chunkRepository, Clock.systemUTC());
    }

    public DocumentDeletionExecutor(
        DocumentRepository documentRepository,
        KnowledgeChunkRepository chunkRepository,
        Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.clock = clock;
    }

    @Override
    public DeletionScopeType supportedScope() {
        return DeletionScopeType.DOCUMENT;
    }

    @Override
    public DeletionOutcome execute(String tenantId, String scopeId, String executorId) {
        Map<String, Object> deletedRows = new LinkedHashMap<>();
        java.util.List<String> failures = new java.util.ArrayList<>();

        Optional<DocumentRecord> existing = documentRepository.findById(tenantId, scopeId);
        if (existing.isEmpty()) {
            failures.add("document:" + scopeId + ":not_found");
            log.warn(
                "deletion_executor_document_missing tenant={} doc={} executor={}",
                tenantId, scopeId, executorId
            );
            return new DeletionOutcome(deletedRows, Map.of(), Map.of(), failures);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        documentRepository.softDelete(tenantId, scopeId, now);
        deletedRows.put("documents", 1);

        // Cascade: mark all RAG chunks for the document stale so the
        // retrieval second-pass filter drops them immediately. A full
        // status=DELETED cascade is intentionally deferred to keep
        // this executor reversible (a future restore PR can just
        // un-stale them) until the deletion-cascade expansion ships.
        int staled = chunkRepository.markStaleForDocument(tenantId, scopeId);
        if (staled > 0) {
            deletedRows.put("knowledge_chunks_staled", staled);
        }

        log.info(
            "deletion_executor_document_softdelete tenant={} doc={} executor={} chunksStaled={}",
            tenantId, scopeId, executorId, staled
        );
        return new DeletionOutcome(deletedRows, Map.of(), Map.of(), failures);
    }
}
