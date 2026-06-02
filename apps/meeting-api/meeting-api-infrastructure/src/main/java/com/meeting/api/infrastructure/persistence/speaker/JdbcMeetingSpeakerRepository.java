package com.meeting.api.infrastructure.persistence.speaker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.SpeakerCandidate;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMeetingSpeakerRepository implements MeetingSpeakerRepository {
    private static final TypeReference<List<SpeakerCandidate>> SPEAKER_CANDIDATE_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMeetingSpeakerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, speaker_label, global_speaker_label,
                   candidate_person_ids::text AS candidate_person_ids,
                   candidates::text AS candidates,
                   auto_match_score, match_source, verification_status,
                   confirmed_person_id, confirmed_speaker_profile_id,
                   confirmed_by, confirmed_at, created_at, updated_at
              FROM meeting_speakers
             WHERE tenant_id = ? AND meeting_id = ? AND speaker_label = ?
            """,
            rs -> rs.next() ? Optional.of(mapRow(rs)) : Optional.<MeetingSpeakerRecord>empty(),
            tenantId,
            meetingId,
            speakerLabel
        );
    }

    @Override
    public List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, speaker_label, global_speaker_label,
                   candidate_person_ids::text AS candidate_person_ids,
                   candidates::text AS candidates,
                   auto_match_score, match_source, verification_status,
                   confirmed_person_id, confirmed_speaker_profile_id,
                   confirmed_by, confirmed_at, created_at, updated_at
              FROM meeting_speakers
             WHERE tenant_id = ? AND meeting_id = ?
             ORDER BY speaker_label
            """,
            (rs, n) -> mapRow(rs),
            tenantId,
            meetingId
        );
    }

    @Override
    public List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId) {
        return jdbcTemplate.query(
            "SELECT DISTINCT meeting_id FROM meeting_speakers"
                + " WHERE tenant_id = ? AND confirmed_person_id = ? AND verification_status = 'CONFIRMED'",
            (rs, n) -> rs.getString("meeting_id"),
            tenantId,
            personId
        );
    }

    @Override
    public void saveCandidates(String tenantId, String meetingId, String speakerLabel,
                                List<String> candidatePersonIds, Double autoMatchScore, String matchSource,
                                OffsetDateTime now) {
        saveCandidates(tenantId, meetingId, speakerLabel, candidatePersonIds, List.of(), autoMatchScore, matchSource, now);
    }

    @Override
    public void saveCandidates(String tenantId, String meetingId, String speakerLabel,
                                List<String> candidatePersonIds, List<SpeakerCandidate> candidates,
                                Double autoMatchScore, String matchSource, OffsetDateTime now) {
        List<String> normalizedCandidatePersonIds = normalizeCandidatePersonIds(candidatePersonIds, candidates);
        String candidatePersonIdsJson = toJson(normalizedCandidatePersonIds);
        String candidatesJson = toJson(candidates);
        int updated = jdbcTemplate.update(
            """
            UPDATE meeting_speakers
               SET candidate_person_ids = ?::jsonb, candidates = ?::jsonb,
                   auto_match_score = ?, match_source = ?, updated_at = ?
             WHERE tenant_id = ? AND meeting_id = ? AND speaker_label = ?
            """,
            candidatePersonIdsJson,
            candidatesJson,
            autoMatchScore,
            matchSource,
            Timestamp.from(now.toInstant()),
            tenantId,
            meetingId,
            speakerLabel
        );
        if (updated == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO meeting_speakers (
                  id, tenant_id, meeting_id, speaker_label, candidate_person_ids, candidates,
                  auto_match_score, match_source, verification_status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'CANDIDATE', ?, ?)
                """,
                "msp_" + UUID.randomUUID().toString().replace("-", ""),
                tenantId,
                meetingId,
                speakerLabel,
                candidatePersonIdsJson,
                candidatesJson,
                autoMatchScore,
                matchSource,
                Timestamp.from(now.toInstant()),
                Timestamp.from(now.toInstant())
            );
        }
    }

    @Override
    public void confirm(String tenantId, String meetingId, String speakerLabel,
                         String confirmedPersonId, String confirmedSpeakerProfileId, String confirmedBy, OffsetDateTime now) {
        jdbcTemplate.update(
            """
            UPDATE meeting_speakers
               SET verification_status = 'CONFIRMED', confirmed_person_id = ?, confirmed_speaker_profile_id = ?, confirmed_by = ?,
                   confirmed_at = ?, updated_at = ?
             WHERE tenant_id = ? AND meeting_id = ? AND speaker_label = ?
            """,
            confirmedPersonId,
            confirmedSpeakerProfileId,
            confirmedBy,
            Timestamp.from(now.toInstant()),
            Timestamp.from(now.toInstant()),
            tenantId,
            meetingId,
            speakerLabel
        );
    }

    @Override
    public void reject(String tenantId, String meetingId, String speakerLabel,
                        String rejectedBy, OffsetDateTime now) {
        jdbcTemplate.update(
            """
            UPDATE meeting_speakers
               SET verification_status = 'REJECTED', confirmed_by = ?, updated_at = ?
             WHERE tenant_id = ? AND meeting_id = ? AND speaker_label = ?
            """,
            rejectedBy,
            Timestamp.from(now.toInstant()),
            tenantId,
            meetingId,
            speakerLabel
        );
    }

    private MeetingSpeakerRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Double autoMatchScore = rs.getObject("auto_match_score") == null ? null : rs.getDouble("auto_match_score");
        return new MeetingSpeakerRecord(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("meeting_id"),
            rs.getString("speaker_label"),
            rs.getString("global_speaker_label"),
            fromJson(rs.getString("candidate_person_ids")),
            candidatesFromJson(rs.getString("candidates")),
            autoMatchScore,
            rs.getString("match_source"),
            rs.getString("verification_status"),
            rs.getString("confirmed_person_id"),
            rs.getString("confirmed_speaker_profile_id"),
            rs.getString("confirmed_by"),
            rs.getObject("confirmed_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            List<String> result = new ArrayList<>();
            for (Object o : raw) {
                if (o != null) result.add(o.toString());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SpeakerCandidate> candidatesFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<SpeakerCandidate> candidates = objectMapper.readValue(json, SPEAKER_CANDIDATE_LIST_TYPE);
            return candidates == null ? List.of() : candidates;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> normalizeCandidatePersonIds(List<String> candidatePersonIds, List<SpeakerCandidate> candidates) {
        if (candidatePersonIds != null) {
            return candidatePersonIds;
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(SpeakerCandidate::personId)
            .filter(personId -> personId != null && !personId.isBlank())
            .toList();
    }
}
