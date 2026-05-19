package com.meeting.api.infrastructure.persistence.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Workstation D2 — glossary terms live inline on {@code meetings.glossary_terms jsonb}.
 * Reading the meeting row is acceptable (single-key lookup); writing uses a single UPDATE
 * with a jsonb cast.
 */
@Repository
public class JdbcMeetingGlossaryRepository implements MeetingGlossaryRepository {
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP =
        new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMeetingGlossaryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<List<GlossaryTerm>> findByMeetingId(String tenantId, String meetingId) {
        List<String> rows = jdbcTemplate.query(
            """
            SELECT glossary_terms::text AS terms
              FROM meetings
             WHERE tenant_id = ? AND id = ?
            """,
            (rs, rowNum) -> rs.getString("terms"),
            tenantId, meetingId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String json = rows.get(0);
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return Optional.of(List.of());
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, LIST_OF_MAP);
            List<GlossaryTerm> terms = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                Object term = entry.get("term");
                if (term == null) continue;
                Object definition = entry.get("definition");
                Object aliases = entry.get("aliases");
                List<String> aliasList;
                if (aliases instanceof List<?> list) {
                    aliasList = list.stream().filter(java.util.Objects::nonNull)
                        .map(Object::toString).toList();
                } else {
                    aliasList = List.of();
                }
                terms.add(new GlossaryTerm(
                    term.toString(),
                    definition == null ? null : definition.toString(),
                    aliasList
                ));
            }
            return Optional.of(terms);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "glossary_terms jsonb is malformed for meeting=" + meetingId, ex
            );
        }
    }

    @Override
    public OffsetDateTime replace(String tenantId, String meetingId, List<GlossaryTerm> terms, OffsetDateTime now) {
        String json;
        try {
            json = objectMapper.writeValueAsString(
                terms.stream()
                    .map(t -> {
                        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("term", t.term());
                        if (t.definition() != null) m.put("definition", t.definition());
                        if (t.aliases() != null && !t.aliases().isEmpty()) m.put("aliases", t.aliases());
                        return m;
                    })
                    .toList()
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize glossary terms", ex);
        }
        int affected = jdbcTemplate.update(
            """
            UPDATE meetings
               SET glossary_terms = ?::jsonb,
                   updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """,
            json,
            Timestamp.from(now.toInstant()),
            tenantId, meetingId
        );
        if (affected == 0) {
            throw new IllegalStateException("meeting not found while writing glossary: " + meetingId);
        }
        return now.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
