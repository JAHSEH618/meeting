package com.meeting.api.infrastructure.persistence.document;

import com.meeting.api.domain.document.DocumentChunkRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentChunkRepository implements DocumentChunkRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void replaceChunks(String tenantId, String documentId, List<ChunkRecord> chunks, OffsetDateTime now) {
        jdbcTemplate.update(
            "DELETE FROM document_chunks WHERE tenant_id = ? AND document_id = ?",
            tenantId,
            documentId
        );
        for (ChunkRecord chunk : chunks) {
            jdbcTemplate.update(
                """
                INSERT INTO document_chunks (
                  id, tenant_id, document_id, chunk_index, page_number,
                  content, content_hash, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """,
                chunk.id(),
                chunk.tenantId(),
                chunk.documentId(),
                chunk.chunkIndex(),
                chunk.pageNumber(),
                chunk.content(),
                chunk.contentHash(),
                Timestamp.from(now.toInstant())
            );
        }
    }

    @Override
    public List<ChunkRecord> findByDocument(String tenantId, String documentId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, document_id, chunk_index, page_number, content, content_hash
              FROM document_chunks
             WHERE tenant_id = ? AND document_id = ?
             ORDER BY chunk_index ASC
            """,
            (rs, n) -> new ChunkRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("document_id"),
                rs.getInt("chunk_index"),
                rs.getObject("page_number") == null ? null : rs.getInt("page_number"),
                rs.getString("content"),
                rs.getString("content_hash")
            ),
            tenantId,
            documentId
        );
    }
}
