package com.meeting.api;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort.DeletionOutcome;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.document.DocumentRepository.DocumentRecord;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.infrastructure.gateway.compliance.DocumentDeletionExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDeletionExecutorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T11:00:00Z");

    private InMemoryDocumentRepo docRepo;
    private InMemoryChunkRepo chunkRepo;
    private DocumentDeletionExecutor executor;

    @BeforeEach
    void setUp() {
        docRepo = new InMemoryDocumentRepo();
        chunkRepo = new InMemoryChunkRepo();
        executor = new DocumentDeletionExecutor(
            docRepo, chunkRepo, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    @Test
    void supportsDocumentScope() {
        assertThat(executor.supportedScope()).isEqualTo(DeletionScopeType.DOCUMENT);
    }

    @Test
    void softDeletesDocumentAndStalesChunks() {
        docRepo.rows.put("doc_01", record("doc_01", false));
        chunkRepo.staleByDoc.put("doc_01", 4);

        DeletionOutcome outcome = executor.execute("tenant_01", "doc_01", "deletion-runner");

        assertThat(outcome.deletedRows()).containsEntry("documents", 1);
        assertThat(outcome.deletedRows()).containsEntry("knowledge_chunks_staled", 4);
        assertThat(outcome.failedItems()).isEmpty();
        assertThat(docRepo.softDeleted).containsExactly("doc_01");
        assertThat(chunkRepo.markStaleCalls).containsExactly("doc_01");
    }

    @Test
    void recordsFailureWhenDocumentMissing() {
        DeletionOutcome outcome = executor.execute("tenant_01", "doc_missing", "deletion-runner");

        assertThat(outcome.deletedRows()).isEmpty();
        assertThat(outcome.failedItems()).containsExactly("document:doc_missing:not_found");
        assertThat(docRepo.softDeleted).isEmpty();
    }

    @Test
    void zeroChunksDoesNotAddCounter() {
        docRepo.rows.put("doc_no_chunks", record("doc_no_chunks", false));
        // chunkRepo returns 0 for unknown doc id

        DeletionOutcome outcome = executor.execute("tenant_01", "doc_no_chunks", "deletion-runner");

        assertThat(outcome.deletedRows()).containsEntry("documents", 1);
        assertThat(outcome.deletedRows()).doesNotContainKey("knowledge_chunks_staled");
    }

    private static DocumentRecord record(String id, boolean deleted) {
        return new DocumentRecord(
            id, "tenant_01", null, "Doc " + id, "file_" + id, "PDF", "UPLOADED",
            SecurityLevel.INTERNAL, "READY", null, null, "user_test",
            NOW.minusDays(1), NOW.minusHours(1), deleted ? NOW.minusMinutes(5) : null
        );
    }

    private static class InMemoryDocumentRepo implements DocumentRepository {
        final Map<String, DocumentRecord> rows = new HashMap<>();
        final List<String> softDeleted = new ArrayList<>();

        @Override public String save(DocumentRecord record) {
            rows.put(record.id(), record);
            return record.id();
        }

        @Override public Optional<DocumentRecord> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) {
            return new ArrayList<>(rows.values());
        }

        @Override public void updateExtractionStatus(
            String tenantId, String documentId, String ext, String status, OffsetDateTime now
        ) {
        }

        @Override public void softDelete(String tenantId, String documentId, OffsetDateTime now) {
            softDeleted.add(documentId);
        }
    }

    private static class InMemoryChunkRepo implements KnowledgeChunkRepository {
        final Map<String, Integer> staleByDoc = new HashMap<>();
        final List<String> markStaleCalls = new ArrayList<>();

        @Override public int markStaleForMeeting(String tenantId, String meetingId) {
            return 0;
        }

        @Override public int markStaleForDocument(String tenantId, String documentId) {
            markStaleCalls.add(documentId);
            return staleByDoc.getOrDefault(documentId, 0);
        }

        @Override public int updateStaleStatus(
            String tenantId, Collection<String> chunkIds, StaleStatus newStatus
        ) {
            return 0;
        }
    }
}
