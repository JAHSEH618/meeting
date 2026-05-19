package com.meeting.api.infrastructure.persistence.meeting;

import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.meeting.MeetingDocumentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Workstation D1 — link table persistence.
 * RLS policy on {@code meeting_documents} enforces tenant isolation; callers
 * must set {@code app.tenant_id} via TenantScopedTransaction before invoking.
 */
@Repository
public class JdbcMeetingDocumentRepository implements MeetingDocumentRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcMeetingDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String save(MeetingDocumentRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO meeting_documents (
              id, tenant_id, meeting_id, document_id, role,
              attached_by, attached_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
            """,
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.documentId(),
            record.role().name(),
            record.attachedBy(),
            Timestamp.from(record.attachedAt().toInstant())
        );
        return record.id();
    }

    @Override
    public Optional<MeetingDocumentRecord> findActive(String tenantId, String meetingId, String documentId) {
        List<MeetingDocumentRecord> rows = jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, document_id, role, attached_by, attached_at
              FROM meeting_documents
             WHERE tenant_id = ? AND meeting_id = ? AND document_id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> new MeetingDocumentRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("meeting_id"),
                rs.getString("document_id"),
                DocumentRole.valueOf(rs.getString("role")),
                rs.getString("attached_by"),
                toOffsetDateTime(rs.getTimestamp("attached_at"))
            ),
            tenantId, meetingId, documentId
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<MeetingDocumentJoinRow> listByMeeting(String tenantId, String meetingId) {
        return jdbcTemplate.query(
            """
            SELECT md.id AS link_id, md.meeting_id, md.document_id, md.role,
                   md.attached_by, md.attached_at,
                   d.title AS document_title, d.security_level AS document_security_level
              FROM meeting_documents md
              JOIN documents d ON d.id = md.document_id AND d.tenant_id = md.tenant_id
             WHERE md.tenant_id = ? AND md.meeting_id = ? AND md.deleted_at IS NULL
             ORDER BY md.attached_at DESC, md.id DESC
            """,
            (rs, rowNum) -> mapJoinRow(rs),
            tenantId, meetingId
        );
    }

    @Override
    public boolean softDelete(String tenantId, String meetingId, String documentId, OffsetDateTime now) {
        int affected = jdbcTemplate.update(
            """
            UPDATE meeting_documents
               SET deleted_at = ?
             WHERE tenant_id = ? AND meeting_id = ? AND document_id = ? AND deleted_at IS NULL
            """,
            Timestamp.from(now.toInstant()),
            tenantId, meetingId, documentId
        );
        return affected > 0;
    }

    private MeetingDocumentJoinRow mapJoinRow(ResultSet rs) throws SQLException {
        return new MeetingDocumentJoinRow(
            rs.getString("link_id"),
            rs.getString("meeting_id"),
            rs.getString("document_id"),
            rs.getString("document_title"),
            DocumentRole.valueOf(rs.getString("role")),
            SecurityLevel.valueOf(rs.getString("document_security_level")),
            rs.getString("attached_by"),
            toOffsetDateTime(rs.getTimestamp("attached_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
