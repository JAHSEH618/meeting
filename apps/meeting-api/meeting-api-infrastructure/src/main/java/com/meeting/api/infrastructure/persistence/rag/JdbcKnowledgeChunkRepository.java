package com.meeting.api.infrastructure.persistence.rag;

import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
