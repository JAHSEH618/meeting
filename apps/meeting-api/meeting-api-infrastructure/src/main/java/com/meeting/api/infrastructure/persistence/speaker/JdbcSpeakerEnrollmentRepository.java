package com.meeting.api.infrastructure.persistence.speaker;

import com.meeting.api.domain.speaker.SpeakerEnrollmentRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSpeakerEnrollmentRepository implements SpeakerEnrollmentRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcSpeakerEnrollmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String save(SpeakerEnrollmentRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO speaker_enrollments (
              id, tenant_id, speaker_profile_id, source_audio_file_id, enrollment_status,
              quality_score, model_version, artifact_uri, error_code, created_by,
              created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.id(),
            record.tenantId(),
            record.speakerProfileId(),
            record.sourceAudioFileId(),
            record.enrollmentStatus(),
            record.qualityScore(),
            record.modelVersion(),
            record.artifactUri(),
            record.errorCode(),
            record.createdBy(),
            Timestamp.from(record.createdAt().toInstant()),
            Timestamp.from(record.updatedAt().toInstant())
        );
        return record.id();
    }

    @Override
    public Optional<SpeakerEnrollmentRecord> findById(String tenantId, String enrollmentId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, speaker_profile_id, source_audio_file_id, enrollment_status,
                   quality_score, model_version, artifact_uri, error_code, created_by,
                   created_at, updated_at
              FROM speaker_enrollments
             WHERE tenant_id = ? AND id = ?
            """,
            rs -> rs.next() ? Optional.of(mapRow(rs)) : Optional.<SpeakerEnrollmentRecord>empty(),
            tenantId,
            enrollmentId
        );
    }

    @Override
    public List<SpeakerEnrollmentRecord> findByProfile(String tenantId, String profileId) {
        return jdbcTemplate.query(
            "SELECT id, tenant_id, speaker_profile_id, source_audio_file_id, enrollment_status,"
                + " quality_score, model_version, artifact_uri, error_code, created_by, created_at, updated_at"
                + " FROM speaker_enrollments WHERE tenant_id = ? AND speaker_profile_id = ? ORDER BY created_at DESC",
            (rs, n) -> mapRow(rs),
            tenantId,
            profileId
        );
    }

    @Override
    public void updateStatus(String tenantId, String enrollmentId, String enrollmentStatus,
                              Double qualityScore, String modelVersion, String errorCode, OffsetDateTime now) {
        jdbcTemplate.update(
            """
            UPDATE speaker_enrollments
               SET enrollment_status = ?, quality_score = ?, model_version = ?, error_code = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """,
            enrollmentStatus,
            qualityScore,
            modelVersion,
            errorCode,
            Timestamp.from(now.toInstant()),
            tenantId,
            enrollmentId
        );
    }

    private static SpeakerEnrollmentRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Double qualityScore = rs.getObject("quality_score") == null ? null : rs.getDouble("quality_score");
        return new SpeakerEnrollmentRecord(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("speaker_profile_id"),
            rs.getString("source_audio_file_id"),
            rs.getString("enrollment_status"),
            qualityScore,
            rs.getString("model_version"),
            rs.getString("artifact_uri"),
            rs.getString("error_code"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
