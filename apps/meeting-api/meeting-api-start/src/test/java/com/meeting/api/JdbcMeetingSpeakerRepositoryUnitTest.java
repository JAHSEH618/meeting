package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.infrastructure.persistence.speaker.JdbcMeetingSpeakerRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMeetingSpeakerRepositoryUnitTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-02T03:00:00Z");

    @Test
    void saveCandidatesWritesFullCandidateJsonAndDerivedPersonIds() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        MeetingSpeakerRepository repo = new JdbcMeetingSpeakerRepository(jdbc, new ObjectMapper());

        repo.saveCandidates(
            "tenant_01",
            "meeting_01",
            "SPEAKER_00",
            List.of("person_01"),
            List.of(new MeetingSpeakerRepository.SpeakerCandidate("person_01", "profile_01", 0.91)),
            0.91,
            "AI_MATCH",
            NOW
        );

        assertThat(jdbc.sql).contains("candidate_person_ids", "candidates");
        assertThat(jdbc.args[0].toString()).isEqualTo("[\"person_01\"]");
        assertThat(jdbc.args[1].toString())
            .contains("\"personId\":\"person_01\"")
            .contains("\"speakerProfileId\":\"profile_01\"")
            .contains("\"confidence\":0.91");
    }

    @Test
    void confirmWritesConfirmedSpeakerProfileId() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        MeetingSpeakerRepository repo = new JdbcMeetingSpeakerRepository(jdbc, new ObjectMapper());

        repo.confirm(
            "tenant_01",
            "meeting_01",
            "SPEAKER_00",
            "person_01",
            "profile_01",
            "user_01",
            NOW
        );

        assertThat(jdbc.sql).contains("confirmed_speaker_profile_id");
        assertThat(jdbc.args[0]).isEqualTo("person_01");
        assertThat(jdbc.args[1]).isEqualTo("profile_01");
        assertThat(jdbc.args[2]).isEqualTo("user_01");
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
