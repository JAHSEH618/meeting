package com.meeting.api.infrastructure.persistence.storage;

import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMeetingFileRepository implements MeetingFileRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcMeetingFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MeetingFile save(MeetingFile file) {
        jdbcTemplate.update(
            """
            INSERT INTO meeting_files (
              id, tenant_id, meeting_id, file_type, file_purpose, file_name,
              content_type, bucket, object_key, uri, size_bytes, sha256,
              duration_ms, upload_status, created_by, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, uri) DO UPDATE SET
              file_name = EXCLUDED.file_name,
              content_type = EXCLUDED.content_type,
              size_bytes = EXCLUDED.size_bytes,
              sha256 = EXCLUDED.sha256,
              duration_ms = EXCLUDED.duration_ms,
              upload_status = EXCLUDED.upload_status
            """,
            file.fileId(),
            file.tenantId(),
            file.meetingId(),
            file.fileType(),
            file.filePurpose(),
            file.fileName(),
            file.contentType(),
            file.bucket(),
            file.objectKey(),
            file.uri(),
            file.sizeBytes(),
            file.sha256(),
            file.durationMs(),
            file.uploadStatus(),
            file.createdBy(),
            toTimestamp(file.createdAt())
        );
        return file;
    }

    @Override
    public Optional<MeetingFile> findById(String tenantId, String fileId) {
        List<MeetingFile> rows = jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, file_type, file_purpose, file_name,
                   content_type, bucket, object_key, uri, size_bytes, sha256,
                   duration_ms, upload_status, created_by, created_at, updated_at
              FROM meeting_files
             WHERE tenant_id = ? AND id = ?
            """,
            rowMapper(),
            tenantId, fileId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private RowMapper<MeetingFile> rowMapper() {
        return (rs, rowNum) -> new MeetingFile(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("meeting_id"),
            rs.getString("file_type"),
            rs.getString("file_purpose"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getString("bucket"),
            rs.getString("object_key"),
            rs.getString("uri"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            getNullableLong(rs, "duration_ms"),
            rs.getString("upload_status"),
            rs.getString("created_by"),
            odt(rs, "created_at"),
            odt(rs, "updated_at")
        );
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}
