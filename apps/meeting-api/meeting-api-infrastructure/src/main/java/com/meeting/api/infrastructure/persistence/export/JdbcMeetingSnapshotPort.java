package com.meeting.api.infrastructure.persistence.export;

import com.meeting.api.domain.export.MeetingSnapshotPort;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Version-locked, RLS-respecting snapshot loader for the export
 * pipeline. Returns {@link Optional#empty()} on any version mismatch
 * or missing meeting so the caller (export application service) can
 * translate to {@code EXPORT_CONTENT_STALE}.
 *
 * <p>Joins the meeting row to its transcript / minutes / action items /
 * decisions / risks / speakers, filtered by the requested versions.
 * The result is a fully-materialised value object, intentionally
 * decoupled from any aggregate-layer types.
 */
@Repository
public class JdbcMeetingSnapshotPort implements MeetingSnapshotPort {

    private final JdbcTemplate jdbc;

    public JdbcMeetingSnapshotPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MeetingSnapshot> loadSnapshot(
        String tenantId, String meetingId,
        int transcriptVersion, Integer minutesVersion
    ) {
        Optional<MeetingHeader> header = loadHeader(tenantId, meetingId, transcriptVersion, minutesVersion);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        MeetingHeader h = header.get();
        return Optional.of(new MeetingSnapshot(
            meetingId,
            h.title(),
            h.language(),
            h.durationSeconds(),
            transcriptVersion,
            minutesVersion,
            loadSegments(tenantId, meetingId, transcriptVersion),
            loadMinutes(tenantId, meetingId, minutesVersion),
            loadActionItems(tenantId, meetingId),
            loadDecisions(tenantId, meetingId),
            loadRisks(tenantId, meetingId),
            loadSpeakers(tenantId, meetingId)
        ));
    }

    private Optional<MeetingHeader> loadHeader(
        String tenantId, String meetingId,
        int transcriptVersion, Integer minutesVersion
    ) {
        return jdbc.query(
            """
            SELECT title, language, duration_seconds,
                   transcript_version, minutes_version
              FROM meetings
             WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
             LIMIT 1
            """,
            rs -> {
                if (!rs.next()) return Optional.<MeetingHeader>empty();
                int currentTranscript = rs.getInt("transcript_version");
                int currentMinutes = rs.getInt("minutes_version");
                if (currentTranscript != transcriptVersion) {
                    return Optional.<MeetingHeader>empty();
                }
                if (minutesVersion != null && currentMinutes != minutesVersion) {
                    return Optional.<MeetingHeader>empty();
                }
                long duration = rs.getLong("duration_seconds");
                return Optional.of(new MeetingHeader(
                    rs.getString("title"),
                    rs.getString("language"),
                    rs.wasNull() ? null : duration
                ));
            },
            tenantId, meetingId
        );
    }

    private List<TranscriptSegmentRow> loadSegments(
        String tenantId, String meetingId, int transcriptVersion
    ) {
        return jdbc.query(
            """
            SELECT id, segment_index, start_ms, end_ms,
                   speaker_label, speaker_name, text
              FROM transcript_segments
             WHERE tenant_id = ? AND meeting_id = ? AND transcript_version = ?
             ORDER BY segment_index ASC
            """,
            (rs, rowNum) -> new TranscriptSegmentRow(
                rs.getString("id"),
                rs.getInt("segment_index"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms"),
                rs.getString("speaker_label"),
                rs.getString("speaker_name"),
                rs.getString("text")
            ),
            tenantId, meetingId, transcriptVersion
        );
    }

    private MinutesRow loadMinutes(String tenantId, String meetingId, Integer minutesVersion) {
        if (minutesVersion == null || minutesVersion == 0) return null;
        return jdbc.query(
            """
            SELECT minutes_version, title, markdown
              FROM meeting_minutes
             WHERE tenant_id = ? AND meeting_id = ? AND minutes_version = ?
               AND stale_status = 'ACTIVE'
             LIMIT 1
            """,
            rs -> rs.next()
                ? new MinutesRow(
                    rs.getInt("minutes_version"),
                    rs.getString("title"),
                    rs.getString("markdown")
                )
                : null,
            tenantId, meetingId, minutesVersion
        );
    }

    private List<ActionItemRow> loadActionItems(String tenantId, String meetingId) {
        return jdbc.query(
            """
            SELECT id, title, description, owner_raw_text, deadline_raw_text,
                   priority, status
              FROM meeting_action_items
             WHERE tenant_id = ? AND meeting_id = ?
               AND stale_status = 'ACTIVE'
               AND acceptance_status IN ('ACCEPTED', 'DRAFT', 'NEEDS_REVIEW')
             ORDER BY created_at ASC
            """,
            (rs, rowNum) -> new ActionItemRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("owner_raw_text"),
                rs.getString("deadline_raw_text"),
                rs.getString("priority"),
                rs.getString("status")
            ),
            tenantId, meetingId
        );
    }

    private List<DecisionRow> loadDecisions(String tenantId, String meetingId) {
        return jdbc.query(
            """
            SELECT id, title, description, status
              FROM meeting_decisions
             WHERE tenant_id = ? AND meeting_id = ?
               AND stale_status = 'ACTIVE'
               AND acceptance_status IN ('ACCEPTED', 'DRAFT', 'NEEDS_REVIEW')
             ORDER BY created_at ASC
            """,
            (rs, rowNum) -> new DecisionRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status")
            ),
            tenantId, meetingId
        );
    }

    private List<RiskRow> loadRisks(String tenantId, String meetingId) {
        return jdbc.query(
            """
            SELECT id, title, description, severity, status
              FROM meeting_risks
             WHERE tenant_id = ? AND meeting_id = ?
               AND stale_status = 'ACTIVE'
               AND acceptance_status IN ('ACCEPTED', 'DRAFT', 'NEEDS_REVIEW')
             ORDER BY created_at ASC
            """,
            (rs, rowNum) -> new RiskRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("severity"),
                rs.getString("status")
            ),
            tenantId, meetingId
        );
    }

    private List<MeetingSpeakerRow> loadSpeakers(String tenantId, String meetingId) {
        return jdbc.query(
            """
            SELECT ms.speaker_label,
                   COALESCE(p.display_name, ms.speaker_label) AS display_name,
                   ms.verification_status
              FROM meeting_speakers ms
              LEFT JOIN persons p ON p.id = ms.confirmed_person_id
             WHERE ms.tenant_id = ? AND ms.meeting_id = ?
             ORDER BY ms.speaker_label ASC
            """,
            (rs, rowNum) -> new MeetingSpeakerRow(
                rs.getString("speaker_label"),
                rs.getString("display_name"),
                rs.getString("verification_status")
            ),
            tenantId, meetingId
        );
    }

    private record MeetingHeader(
        String title,
        String language,
        Long durationSeconds
    ) {}
}
