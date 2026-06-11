package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.document.DocumentApplicationService;
import com.meeting.api.client.document.CreateDocumentCommand;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T09:00:00Z");

    @Test
    void createPersistsDocumentWithUploadedStatusAndPendingExtraction() {
        InMemoryDocumentRepo docs = new InMemoryDocumentRepo();
        var service = service(docs, new CountingChunkRepo());

        var dto = service.create(new CreateDocumentCommand(
            "tenant_01", "API 设计草案", "file_01", "PDF",
            SecurityLevel.INTERNAL, "sha256:abc",
            "user_01", "req_01", "trace_01", "idem_01"
        ));

        assertThat(dto.documentId()).startsWith("doc_");
        assertThat(dto.status()).isEqualTo("UPLOADED");
        assertThat(dto.textExtractionStatus()).isEqualTo("PENDING");
        assertThat(docs.store).containsKey(dto.documentId());
    }

    @Test
    void listExcludesSoftDeleted() {
        InMemoryDocumentRepo docs = new InMemoryDocumentRepo();
        var service = service(docs, new CountingChunkRepo());
        var a = service.create(cmd("doc-a"));
        service.create(cmd("doc-b"));

        service.delete("tenant_01", a.documentId(), "user_01");

        var listed = service.list("tenant_01");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).title()).isEqualTo("doc-b");
    }

    @Test
    void reindexMarksDocumentRebuildingAndStalesRagChunks() {
        InMemoryDocumentRepo docs = new InMemoryDocumentRepo();
        CountingChunkRepo chunks = new CountingChunkRepo();
        var service = service(docs, chunks);
        var created = service.create(cmd("plan"));

        var dto = service.reindex("tenant_01", created.documentId(), "user_01");

        assertThat(dto.status()).isEqualTo("REINDEXING");
        assertThat(dto.textExtractionStatus()).isEqualTo("PENDING");
        assertThat(chunks.documentStaleCalls).containsExactly(created.documentId());
    }

    @Test
    void reindexFailsOnDeletedDocument() {
        InMemoryDocumentRepo docs = new InMemoryDocumentRepo();
        var service = service(docs, new CountingChunkRepo());
        var created = service.create(cmd("plan"));
        service.delete("tenant_01", created.documentId(), "user_01");

        assertThatThrownBy(() -> service.reindex("tenant_01", created.documentId(), "user_01"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deleted");
    }

    private static CreateDocumentCommand cmd(String title) {
        return new CreateDocumentCommand(
            "tenant_01", title, "file_" + title, "PDF",
            SecurityLevel.INTERNAL, "sha256:" + title,
            "user_01", "req_" + title, "trace", "idem_" + title
        );
    }

    private static DocumentApplicationService service(InMemoryDocumentRepo docs, CountingChunkRepo chunks) {
        return new DocumentApplicationService(
            docs, chunks, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static final class InMemoryDocumentRepo implements DocumentRepository {
        final Map<String, DocumentRecord> store = new LinkedHashMap<>();

        @Override
        public String save(DocumentRecord record) {
            store.put(record.id(), record);
            return record.id();
        }

        @Override
        public Optional<DocumentRecord> findById(String tenantId, String documentId) {
            return Optional.ofNullable(store.get(documentId)).filter(d -> tenantId.equals(d.tenantId()));
        }

        @Override
        public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) {
            return store.values().stream()
                .filter(d -> tenantId.equals(d.tenantId()))
                .filter(d -> includeDeleted || d.deletedAt() == null)
                .toList();
        }

        @Override
        public void updateExtractionStatus(String tenantId, String documentId, String extractionStatus, String status, OffsetDateTime now) {
            var existing = store.get(documentId);
            if (existing == null) return;
            store.put(documentId, new DocumentRecord(
                existing.id(), existing.tenantId(), existing.projectId(),
                existing.title(), existing.fileId(), existing.documentType(),
                status, existing.securityLevel(), extractionStatus,
                existing.sourceUri(), existing.contentHash(), existing.createdBy(),
                existing.createdAt(), now, existing.deletedAt()
            ));
        }

        @Override
        public void softDelete(String tenantId, String documentId, OffsetDateTime now) {
            var existing = store.get(documentId);
            if (existing == null) return;
            store.put(documentId, new DocumentRecord(
                existing.id(), existing.tenantId(), existing.projectId(),
                existing.title(), existing.fileId(), existing.documentType(),
                "DELETED", existing.securityLevel(), existing.textExtractionStatus(),
                existing.sourceUri(), existing.contentHash(), existing.createdBy(),
                existing.createdAt(), now, now
            ));
        }
    }

    private static final class CountingChunkRepo implements KnowledgeChunkRepository {
        final List<String> documentStaleCalls = new ArrayList<>();

        @Override
        public int markStaleForMeeting(String tenantId, String meetingId) {
            return 0;
        }

        @Override
        public int markStaleForDocument(String tenantId, String documentId) {
            documentStaleCalls.add(documentId);
            return 1;
        }
    }
}
