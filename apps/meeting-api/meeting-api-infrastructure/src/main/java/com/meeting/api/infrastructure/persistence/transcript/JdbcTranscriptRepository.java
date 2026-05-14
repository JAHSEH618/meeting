package com.meeting.api.infrastructure.persistence.transcript;

import com.meeting.api.domain.transcript.TranscriptRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTranscriptRepository implements TranscriptRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTranscriptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int currentTranscriptVersion(String tenantId, String meetingId) {
        Integer version = jdbcTemplate.query(
            "SELECT transcript_version FROM meetings WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
            rs -> rs.next() ? rs.getInt("transcript_version") : null,
            tenantId,
            meetingId
        );
        if (version == null) {
            throw new IllegalArgumentException("meeting not found: " + meetingId);
        }
        return version;
    }

    @Override
    public List<TranscriptSegmentRecord> findByMeeting(String tenantId, String meetingId, int transcriptVersion) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, segment_index, start_ms, end_ms,
                   speaker_label, speaker_name, original_text, edited_text, text,
                   asr_confidence, diarization_confidence, speaker_confidence,
                   timestamp_precision, transcript_version, artifact_manifest_id
              FROM transcript_segments
             WHERE tenant_id = ? AND meeting_id = ? AND transcript_version = ?
             ORDER BY segment_index ASC
            """,
            (rs, rowNum) -> new TranscriptSegmentRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("meeting_id"),
                rs.getInt("segment_index"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms"),
                rs.getString("speaker_label"),
                rs.getString("speaker_name"),
                rs.getString("original_text"),
                rs.getString("edited_text"),
                rs.getString("text"),
                rs.getBigDecimal("asr_confidence"),
                rs.getBigDecimal("diarization_confidence"),
                rs.getBigDecimal("speaker_confidence"),
                rs.getString("timestamp_precision"),
                rs.getInt("transcript_version"),
                rs.getString("artifact_manifest_id")
            ),
            tenantId,
            meetingId,
            transcriptVersion
        );
    }

    @Override
    public Optional<TranscriptSegmentRecord> findSegment(String tenantId, String meetingId, String segmentId, int transcriptVersion) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, segment_index, start_ms, end_ms,
                   speaker_label, speaker_name, original_text, edited_text, text,
                   asr_confidence, diarization_confidence, speaker_confidence,
                   timestamp_precision, transcript_version, artifact_manifest_id
              FROM transcript_segments
             WHERE tenant_id = ? AND meeting_id = ? AND id = ? AND transcript_version = ?
            """,
            rs -> rs.next() ? Optional.of(new TranscriptSegmentRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("meeting_id"),
                rs.getInt("segment_index"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms"),
                rs.getString("speaker_label"),
                rs.getString("speaker_name"),
                rs.getString("original_text"),
                rs.getString("edited_text"),
                rs.getString("text"),
                rs.getBigDecimal("asr_confidence"),
                rs.getBigDecimal("diarization_confidence"),
                rs.getBigDecimal("speaker_confidence"),
                rs.getString("timestamp_precision"),
                rs.getInt("transcript_version"),
                rs.getString("artifact_manifest_id")
            )) : Optional.<TranscriptSegmentRecord>empty(),
            tenantId,
            meetingId,
            segmentId,
            transcriptVersion
        );
    }

    @Override
    public void applySegmentEdit(
        String tenantId,
        String meetingId,
        String segmentId,
        int expectedTranscriptVersion,
        String editedText,
        String changedBy,
        String editReason,
        OffsetDateTime now
    ) {
        String beforeText = jdbcTemplate.query(
            "SELECT text FROM transcript_segments WHERE tenant_id = ? AND meeting_id = ? AND id = ? AND transcript_version = ?",
            rs -> rs.next() ? rs.getString("text") : null,
            tenantId,
            meetingId,
            segmentId,
            expectedTranscriptVersion
        );
        if (beforeText == null) {
            throw new IllegalArgumentException("segment not found: " + segmentId);
        }
        int updated = jdbcTemplate.update(
            """
            UPDATE transcript_segments
               SET edited_text = ?, text = ?, edit_status = 'EDITED', updated_at = ?
             WHERE tenant_id = ? AND meeting_id = ? AND id = ? AND transcript_version = ?
            """,
            editedText,
            editedText,
            Timestamp.from(now.toInstant()),
            tenantId,
            meetingId,
            segmentId,
            expectedTranscriptVersion
        );
        if (updated == 0) {
            throw new IllegalStateException("transcript segment edit lost a race; retry with current version");
        }
        jdbcTemplate.update(
            """
            INSERT INTO transcript_change_events (
              id, tenant_id, meeting_id, segment_id, before_text, after_text,
              before_transcript_version, after_transcript_version, edit_reason, changed_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "tce_" + UUID.randomUUID().toString().replace("-", ""),
            tenantId,
            meetingId,
            segmentId,
            beforeText,
            editedText,
            expectedTranscriptVersion,
            expectedTranscriptVersion,
            editReason,
            changedBy,
            Timestamp.from(now.toInstant())
        );
    }

    @Override
    public void replaceTranscript(String tenantId, String meetingId, int transcriptVersion, String artifactManifestId, List<TranscriptSegmentRecord> segments) {
        jdbcTemplate.update(
            "DELETE FROM transcript_segments WHERE tenant_id = ? AND meeting_id = ? AND transcript_version = ?",
            tenantId,
            meetingId,
            transcriptVersion
        );
        for (TranscriptSegmentRecord segment : segments) {
            jdbcTemplate.update(
                """
                INSERT INTO transcript_segments (
                  id, tenant_id, meeting_id, segment_index, start_ms, end_ms,
                  speaker_label, speaker_name, text, original_text, edited_text,
                  asr_confidence, diarization_confidence, speaker_confidence,
                  timestamp_precision, transcript_version, artifact_manifest_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''))
                """,
                segment.segmentId(),
                tenantId,
                meetingId,
                segment.segmentIndex(),
                segment.startMs(),
                segment.endMs(),
                segment.speakerLabel(),
                segment.speakerDisplayName(),
                segment.currentText(),
                segment.originalText(),
                segment.editedText(),
                scale(segment.asrConfidence()),
                scale(segment.diarizationConfidence()),
                scale(segment.speakerConfidence()),
                segment.timestampPrecision(),
                transcriptVersion,
                artifactManifestId == null ? "" : artifactManifestId
            );
        }
    }

    @Override
    public void updateMeetingTranscriptVersion(String tenantId, String meetingId, int transcriptVersion) {
        jdbcTemplate.update(
            """
            UPDATE meetings
               SET transcript_version = ?
             WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """,
            transcriptVersion,
            tenantId,
            meetingId
        );
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
