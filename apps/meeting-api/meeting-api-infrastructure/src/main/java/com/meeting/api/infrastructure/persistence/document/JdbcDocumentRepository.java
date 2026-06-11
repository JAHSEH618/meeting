package com.meeting.api.infrastructure.persistence.document;

import com.meeting.api.domain.document.DocumentRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String save(DocumentRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO documents (
              id, tenant_id, project_id, title, file_id, document_type, status,
              text_extraction_status, source_uri, content_hash,
              created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.id(),
            record.tenantId(),
            record.projectId(),
            record.title(),
            record.fileId(),
            record.documentType(),
            record.status(),
            record.textExtractionStatus(),
            record.sourceUri(),
            record.contentHash(),
            record.createdBy(),
            Timestamp.from(record.createdAt().toInstant()),
            Timestamp.from(record.updatedAt().toInstant())
        );
        return record.id();
    }

    @Override
    public Optional<DocumentRecord> findById(String tenantId, String documentId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, project_id, title, file_id, document_type, status,
                   text_extraction_status,
                   source_uri, content_hash, created_by, created_at, updated_at, deleted_at
              FROM documents
             WHERE tenant_id = ? AND id = ?
            """,
            rs -> rs.next() ? Optional.of(mapRow(rs)) : Optional.<DocumentRecord>empty(),
            tenantId,
            documentId
        );
    }

    @Override
    public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) {
        String filter = includeDeleted ? "" : " AND deleted_at IS NULL";
        return jdbcTemplate.query(
            "SELECT id, tenant_id, project_id, title, file_id, document_type, status,"
                + " text_extraction_status, source_uri,"
                + " content_hash, created_by, created_at, updated_at, deleted_at"
                + " FROM documents WHERE tenant_id = ?" + filter + " ORDER BY created_at DESC",
            (rs, n) -> mapRow(rs),
            tenantId
        );
    }

    @Override
    public void updateExtractionStatus(String tenantId, String documentId, String extractionStatus,
                                        String status, OffsetDateTime now) {
        jdbcTemplate.update(
            """
            UPDATE documents
               SET text_extraction_status = ?, status = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """,
            extractionStatus,
            status,
            Timestamp.from(now.toInstant()),
            tenantId,
            documentId
        );
    }

    @Override
    public void softDelete(String tenantId, String documentId, OffsetDateTime now) {
        jdbcTemplate.update(
            "UPDATE documents SET deleted_at = ?, status = 'DELETED', updated_at = ?"
                + " WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
            Timestamp.from(now.toInstant()),
            Timestamp.from(now.toInstant()),
            tenantId,
            documentId
        );
    }

    private static DocumentRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentRecord(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("project_id"),
            rs.getString("title"),
            rs.getString("file_id"),
            rs.getString("document_type"),
            rs.getString("status"),
            rs.getString("text_extraction_status"),
            rs.getString("source_uri"),
            rs.getString("content_hash"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }
}
