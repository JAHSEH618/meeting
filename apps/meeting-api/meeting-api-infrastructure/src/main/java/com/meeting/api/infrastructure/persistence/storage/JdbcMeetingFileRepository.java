package com.meeting.api.infrastructure.persistence.storage;

import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}
