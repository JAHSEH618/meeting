package com.meeting.api.infrastructure.persistence.rag;

import com.meeting.api.domain.rag.KnowledgeChunk;
import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed {@link KnowledgeChunkRepository}.
 *
 * <p>Phase 5 M5A C8 wires the read / write surface in. Vector + keyword
 * retrieval and the bulk embedding writeback land in M5A C10 / C13; the
 * mutation methods below currently no-op so the application layer can
 * already depend on the port without crashing.
 */
@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(Collection<KnowledgeChunk> chunks) {
        // Implemented in M5A C10; intentional no-op until then so domain
        // collaborators can be tested in isolation.
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        throw new UnsupportedOperationException("saveAll lands in M5A C10");
    }

    @Override
    public List<KnowledgeChunk> findByMeetingId(String tenantId, String meetingId) {
        // Implemented in M5A C10.
        return List.of();
    }

    @Override
    public List<KnowledgeChunk> findByDocumentId(String tenantId, String documentId) {
        // Implemented in M5A C10.
        return List.of();
    }

    @Override
    public int markEmbedding(String tenantId, String chunkId, float[] values, String modelVersion) {
        // Implemented in M5A C13 alongside the callback writeback flow.
        throw new UnsupportedOperationException("markEmbedding lands in M5A C13");
    }

    @Override
    public int markEmbeddings(String tenantId, Map<String, EmbeddingResult> embeddingsByChunkId) {
        // Implemented in M5A C13.
        throw new UnsupportedOperationException("markEmbeddings lands in M5A C13");
    }

    @Override
    public List<KnowledgeChunkCandidate> searchByVector(
        String tenantId,
        float[] queryVector,
        RetrievalScope scope,
        int topK
    ) {
        // Implemented in M5B C15.
        return List.of();
    }

    @Override
    public List<KnowledgeChunkCandidate> searchByKeyword(
        String tenantId,
        String queryText,
        RetrievalScope scope,
        int topK
    ) {
        // Implemented in M5B C15.
        return List.of();
    }

    @Override
    public int markStaleForMeeting(String tenantId, String meetingId) {
        return jdbcTemplate.update(
            """
            UPDATE knowledge_chunks
               SET stale_status = 'STALE'::stale_status, updated_at = now()
             WHERE tenant_id = ? AND meeting_id = ? AND stale_status = 'ACTIVE'
            """,
            tenantId,
            meetingId
        );
    }

    @Override
    public int markStaleForDocument(String tenantId, String documentId) {
        return jdbcTemplate.update(
            """
            UPDATE knowledge_chunks
               SET stale_status = 'STALE'::stale_status, updated_at = now()
             WHERE tenant_id = ? AND document_id = ? AND stale_status = 'ACTIVE'
            """,
            tenantId,
            documentId
        );
    }
}
