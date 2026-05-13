package com.meeting.api.infrastructure.persistence.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMeetingRepository implements MeetingRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcMeetingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Meeting save(Meeting meeting) {
        jdbcTemplate.update(
            """
            INSERT INTO meetings (
              id, tenant_id, title, security_level, status, language,
              transcript_version, minutes_version, created_by, created_at
            )
            VALUES (?, ?, ?, ?::security_level, ?::meeting_status, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              title = EXCLUDED.title,
              security_level = EXCLUDED.security_level,
              status = EXCLUDED.status,
              language = EXCLUDED.language,
              transcript_version = EXCLUDED.transcript_version,
              minutes_version = EXCLUDED.minutes_version
            """,
            meeting.id(),
            meeting.tenantId(),
            meeting.title(),
            meeting.securityLevel().name(),
            meeting.status().name(),
            meeting.language(),
            meeting.transcriptVersion(),
            meeting.minutesVersion(),
            meeting.createdBy(),
            Timestamp.from(meeting.createdAt().toInstant())
        );
        replaceParticipants(meeting);
        return meeting;
    }

    @Override
    public Optional<Meeting> findById(String tenantId, String meetingId) {
        List<Meeting> meetings = jdbcTemplate.query(
            """
            SELECT id, tenant_id, title, security_level, status, language,
                   transcript_version, minutes_version, created_by, created_at
              FROM meetings
             WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """,
            this::mapMeeting,
            tenantId,
            meetingId
        );
        return meetings.stream().findFirst();
    }

    @Override
    public List<Meeting> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, title, security_level, status, language,
                   transcript_version, minutes_version, created_by, created_at
              FROM meetings
             WHERE tenant_id = ? AND deleted_at IS NULL
             ORDER BY created_at DESC, id DESC
            """,
            this::mapMeeting,
            tenantId
        );
    }

    private void replaceParticipants(Meeting meeting) {
        jdbcTemplate.update("DELETE FROM meeting_participants WHERE tenant_id = ? AND meeting_id = ?", meeting.tenantId(), meeting.id());
        for (Meeting.Participant participant : meeting.participants()) {
            jdbcTemplate.update(
                """
                INSERT INTO meeting_participants (
                  id, tenant_id, meeting_id, person_id, display_name_snapshot, participant_role
                )
                VALUES (?, ?, ?, NULLIF(?, ''), ?, ?)
                """,
                "mp_" + UUID.randomUUID().toString().replace("-", ""),
                meeting.tenantId(),
                meeting.id(),
                participant.personId(),
                participant.displayName(),
                participant.role()
            );
        }
    }

    private Meeting mapMeeting(ResultSet rs, int rowNum) throws SQLException {
        return new Meeting.Builder()
            .id(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .title(rs.getString("title"))
            .securityLevel(SecurityLevel.valueOf(rs.getString("security_level")))
            .status(MeetingStatus.valueOf(rs.getString("status")))
            .language(rs.getString("language"))
            .transcriptVersion(rs.getInt("transcript_version"))
            .minutesVersion(rs.getInt("minutes_version"))
            .createdBy(rs.getString("created_by"))
            .createdAt(toOffsetDateTime(rs.getTimestamp("created_at")))
            .participants(List.of())
            .build();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
