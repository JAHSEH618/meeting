package com.meeting.api.app.document;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.document.CreateDocumentCommand;
import com.meeting.api.client.document.DocumentDTO;
import com.meeting.api.client.document.DocumentFacade;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.document.DocumentRepository.DocumentRecord;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Document lifecycle: create -> PARSED (worker reports back) -> reindex -> deleted.
 *
 * Parsing itself runs out-of-band (DocumentParser, phase 5 item 2); this service only
 * persists the document row and orchestrates state transitions. Reindex marks RAG
 * chunks STALE for the document so they are picked up by the next embed worker run.
 */
@Service
public class DocumentApplicationService implements DocumentFacade {
    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);

    private final DocumentRepository documentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public DocumentApplicationService(
        DocumentRepository documentRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(documentRepository, knowledgeChunkRepository, tenantScopedTransaction, Clock.systemUTC());
    }

    public DocumentApplicationService(
        DocumentRepository documentRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public DocumentDTO create(CreateDocumentCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.createdBy(), command.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
            DocumentRecord record = new DocumentRecord(
                documentId,
                command.tenantId(),
                null,
                command.title(),
                command.fileId(),
                command.documentType(),
                "UPLOADED",
                command.securityLevel(),
                "PENDING",
                null,
                command.contentHash(),
                command.createdBy(),
                now,
                now,
                null
            );
            documentRepository.save(record);
            log.info("document_created tenant={} doc={} type={}", command.tenantId(), documentId, command.documentType());
            return toDto(record);
        });
    }

    @Override
    public Optional<DocumentDTO> get(String tenantId, String documentId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> documentRepository.findById(tenantId, documentId).map(DocumentApplicationService::toDto));
    }

    @Override
    public List<DocumentDTO> list(String tenantId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> documentRepository.listByTenant(tenantId, false).stream()
                .map(DocumentApplicationService::toDto)
                .toList());
    }

    @Override
    public void delete(String tenantId, String documentId, String deletedBy) {
        tenantScopedTransaction.execute(tenantId, deletedBy, null, () -> {
            DocumentRecord existing = documentRepository.findById(tenantId, documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
            OffsetDateTime now = OffsetDateTime.now(clock);
            documentRepository.softDelete(tenantId, documentId, now);
            log.info("document_deleted tenant={} doc={} by={} type={}", tenantId, documentId, deletedBy, existing.documentType());
            return null;
        });
    }

    @Override
    public DocumentDTO reindex(String tenantId, String documentId, String requestedBy) {
        return tenantScopedTransaction.execute(tenantId, requestedBy, null, () -> {
            DocumentRecord existing = documentRepository.findById(tenantId, documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
            if (existing.deletedAt() != null) {
                throw new IllegalStateException("cannot reindex deleted document: " + documentId);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            documentRepository.updateExtractionStatus(tenantId, documentId, "PENDING", "REINDEXING", now);
            // Mark RAG chunks STALE so the next embed pass re-builds them.
            int staled = knowledgeChunkRepository.markStaleForDocument(tenantId, documentId);
            log.info("document_reindex_queued tenant={} doc={} chunksStaled={}", tenantId, documentId, staled);
            return toDto(new DocumentRecord(
                existing.id(),
                existing.tenantId(),
                existing.projectId(),
                existing.title(),
                existing.fileId(),
                existing.documentType(),
                "REINDEXING",
                existing.securityLevel(),
                "PENDING",
                existing.sourceUri(),
                existing.contentHash(),
                existing.createdBy(),
                existing.createdAt(),
                now,
                existing.deletedAt()
            ));
        });
    }

    private static DocumentDTO toDto(DocumentRecord r) {
        return new DocumentDTO(
            r.id(), r.tenantId(), r.title(), r.fileId(), r.documentType(),
            r.status(), r.securityLevel(), r.textExtractionStatus(),
            r.contentHash(), r.sourceUri(),
            r.createdAt(), r.updatedAt(), r.deletedAt()
        );
    }
}
