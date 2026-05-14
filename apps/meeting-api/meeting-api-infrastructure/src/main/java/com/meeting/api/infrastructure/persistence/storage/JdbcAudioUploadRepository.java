package com.meeting.api.infrastructure.persistence.storage;

import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.domain.storage.AudioUploadPart;
import com.meeting.api.domain.storage.AudioUploadRepository;
import com.meeting.api.domain.storage.AudioUploadSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAudioUploadRepository implements AudioUploadRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAudioUploadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AudioUploadSession saveSession(AudioUploadSession session) {
        jdbcTemplate.update(
            """
            INSERT INTO audio_upload_sessions (
              id, tenant_id, meeting_id, file_id, object_key, bucket, content_type,
              file_name, file_size_bytes, file_sha256, part_size_bytes, max_part_count,
              upload_status, created_by, expires_at, completed_at, aborted_at, created_at
            )
            VALUES (?, ?, ?, NULLIF(?, ''), ?, ?, ?, ?, ?, ?, ?, ?, ?::audio_upload_status, ?,
                    ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              file_id = EXCLUDED.file_id,
              upload_status = EXCLUDED.upload_status,
              completed_at = EXCLUDED.completed_at,
              aborted_at = EXCLUDED.aborted_at
            """,
            session.uploadId(),
            session.tenantId(),
            session.meetingId(),
            nullToEmpty(session.fileId()),
            session.objectKey(),
            session.bucket(),
            session.contentType(),
            session.fileName(),
            session.fileSizeBytes(),
            session.fileSha256(),
            session.partSizeBytes(),
            session.maxPartCount(),
            session.uploadStatus().name(),
            session.createdBy(),
            toTimestamp(session.expiresAt()),
            toTimestamp(session.completedAt()),
            toTimestamp(session.abortedAt()),
            toTimestamp(session.createdAt())
        );
        return session;
    }

    @Override
    public AudioUploadPart savePart(AudioUploadPart part) {
        jdbcTemplate.update(
            """
            INSERT INTO audio_upload_parts (
              id, tenant_id, upload_id, meeting_id, part_number, part_sha256,
              size_bytes, etag, upload_status, uploaded_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?::audio_upload_status, ?, ?)
            ON CONFLICT (upload_id, part_number) DO UPDATE SET
              part_sha256 = EXCLUDED.part_sha256,
              size_bytes = EXCLUDED.size_bytes,
              etag = EXCLUDED.etag,
              upload_status = EXCLUDED.upload_status,
              uploaded_at = EXCLUDED.uploaded_at
            """,
            part.id(),
            part.tenantId(),
            part.uploadId(),
            part.meetingId(),
            part.partNumber(),
            part.partSha256(),
            part.sizeBytes(),
            nullToEmpty(part.etag()),
            part.uploadStatus().name(),
            toTimestamp(part.uploadedAt()),
            toTimestamp(part.createdAt())
        );
        return part;
    }

    @Override
    public Optional<AudioUploadSession> findSession(String tenantId, String uploadId) {
        List<AudioUploadSession> sessions = jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, file_id, object_key, bucket, content_type,
                   file_name, file_size_bytes, file_sha256, part_size_bytes, max_part_count,
                   upload_status, created_by, expires_at, completed_at, aborted_at,
                   created_at, updated_at
              FROM audio_upload_sessions
             WHERE tenant_id = ? AND id = ?
            """,
            this::mapSession,
            tenantId,
            uploadId
        );
        return sessions.stream().findFirst();
    }

    @Override
    public Optional<AudioUploadPart> findPart(String tenantId, String uploadId, int partNumber) {
        List<AudioUploadPart> parts = jdbcTemplate.query(
            """
            SELECT id, tenant_id, upload_id, meeting_id, part_number, part_sha256,
                   size_bytes, etag, upload_status, uploaded_at, created_at, updated_at
              FROM audio_upload_parts
             WHERE tenant_id = ? AND upload_id = ? AND part_number = ?
            """,
            this::mapPart,
            tenantId,
            uploadId,
            partNumber
        );
        return parts.stream().findFirst();
    }

    @Override
    public List<AudioUploadPart> findParts(String tenantId, String uploadId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, upload_id, meeting_id, part_number, part_sha256,
                   size_bytes, etag, upload_status, uploaded_at, created_at, updated_at
              FROM audio_upload_parts
             WHERE tenant_id = ? AND upload_id = ?
             ORDER BY part_number ASC
            """,
            this::mapPart,
            tenantId,
            uploadId
        );
    }

    private AudioUploadSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new AudioUploadSession.Builder()
            .uploadId(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .meetingId(rs.getString("meeting_id"))
            .fileId(rs.getString("file_id"))
            .objectKey(rs.getString("object_key"))
            .bucket(rs.getString("bucket"))
            .contentType(rs.getString("content_type"))
            .fileName(rs.getString("file_name"))
            .fileSizeBytes(rs.getLong("file_size_bytes"))
            .fileSha256(rs.getString("file_sha256"))
            .partSizeBytes(rs.getInt("part_size_bytes"))
            .maxPartCount(rs.getInt("max_part_count"))
            .uploadStatus(AudioUploadStatus.valueOf(rs.getString("upload_status")))
            .createdBy(rs.getString("created_by"))
            .expiresAt(toOffsetDateTime(rs.getTimestamp("expires_at")))
            .completedAt(toOffsetDateTime(rs.getTimestamp("completed_at")))
            .abortedAt(toOffsetDateTime(rs.getTimestamp("aborted_at")))
            .createdAt(toOffsetDateTime(rs.getTimestamp("created_at")))
            .updatedAt(toOffsetDateTime(rs.getTimestamp("updated_at")))
            .build();
    }

    private AudioUploadPart mapPart(ResultSet rs, int rowNum) throws SQLException {
        return new AudioUploadPart(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("upload_id"),
            rs.getString("meeting_id"),
            rs.getInt("part_number"),
            rs.getString("part_sha256"),
            rs.getLong("size_bytes"),
            rs.getString("etag"),
            AudioUploadStatus.valueOf(rs.getString("upload_status")),
            toOffsetDateTime(rs.getTimestamp("uploaded_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
