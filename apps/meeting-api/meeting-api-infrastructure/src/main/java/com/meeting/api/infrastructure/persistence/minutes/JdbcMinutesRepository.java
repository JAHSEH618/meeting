package com.meeting.api.infrastructure.persistence.minutes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.domain.minutes.MinutesRepository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMinutesRepository implements MinutesRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMinutesRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MinutesRecord> findCurrent(String tenantId, String meetingId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, minutes_version, source_transcript_version,
                   title, markdown, structured_json::text AS structured_json, status,
                   stale_status::text AS stale_status, artifact_manifest_id, created_by,
                   created_at, updated_at
              FROM meeting_minutes
             WHERE tenant_id = ? AND meeting_id = ?
             ORDER BY minutes_version DESC
             LIMIT 1
            """,
            rs -> rs.next() ? Optional.of(new MinutesRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("meeting_id"),
                rs.getInt("minutes_version"),
                rs.getInt("source_transcript_version"),
                rs.getString("title"),
                rs.getString("markdown"),
                parseSections(rs.getString("structured_json")),
                rs.getString("status"),
                StaleStatus.valueOf(rs.getString("stale_status")),
                rs.getString("artifact_manifest_id"),
                rs.getString("created_by"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class)
            )) : Optional.<MinutesRecord>empty(),
            tenantId,
            meetingId
        );
    }

    @Override
    public int currentMinutesVersion(String tenantId, String meetingId) {
        Integer version = jdbcTemplate.query(
            "SELECT minutes_version FROM meetings WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
            rs -> rs.next() ? rs.getInt("minutes_version") : null,
            tenantId,
            meetingId
        );
        if (version == null) {
            throw new IllegalArgumentException("meeting not found: " + meetingId);
        }
        return version;
    }

    @Override
    public String save(MinutesRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO meeting_minutes (
              id, tenant_id, meeting_id, minutes_version, source_transcript_version,
              title, markdown, structured_json, status, stale_status, artifact_manifest_id,
              created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::stale_status, ?, ?, ?, ?)
            """,
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.minutesVersion(),
            record.sourceTranscriptVersion(),
            record.title(),
            record.markdown(),
            serializeSections(record.sections()),
            record.status(),
            record.staleStatus().name(),
            record.artifactManifestId(),
            record.createdBy(),
            Timestamp.from(record.createdAt().toInstant()),
            Timestamp.from(record.updatedAt().toInstant())
        );
        return record.id();
    }

    @Override
    public void incrementMeetingMinutesVersion(String tenantId, String meetingId, int newVersion) {
        jdbcTemplate.update(
            "UPDATE meetings SET minutes_version = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
            newVersion,
            tenantId,
            meetingId
        );
    }

    @Override
    public void markStale(String tenantId, String meetingId) {
        jdbcTemplate.update(
            "UPDATE meeting_minutes SET stale_status = 'STALE'::stale_status, updated_at = now()"
                + " WHERE tenant_id = ? AND meeting_id = ? AND stale_status = 'ACTIVE'",
            tenantId,
            meetingId
        );
    }

    private String serializeSections(List<SectionRecord> sections) {
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (var section : sections) {
                Map<String, Object> sectionMap = new LinkedHashMap<>();
                sectionMap.put("type", section.type());
                sectionMap.put("title", section.title());
                List<Map<String, Object>> items = new ArrayList<>();
                for (var item : section.items()) {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    itemMap.put("text", item.text());
                    List<Map<String, Object>> evidence = new ArrayList<>();
                    for (var e : item.evidence()) {
                        Map<String, Object> evMap = new LinkedHashMap<>();
                        evMap.put("segmentId", e.segmentId());
                        evMap.put("startMs", e.startMs());
                        evMap.put("endMs", e.endMs());
                        evMap.put("evidenceTextSnapshot", e.evidenceTextSnapshot());
                        evidence.add(evMap);
                    }
                    itemMap.put("evidence", evidence);
                    items.add(itemMap);
                }
                sectionMap.put("items", items);
                payload.add(sectionMap);
            }
            return objectMapper.writeValueAsString(Map.of("sections", payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize minutes sections", e);
        }
    }

    private List<SectionRecord> parseSections(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<?, ?> root = objectMapper.readValue(json, Map.class);
            Object sections = root.get("sections");
            if (!(sections instanceof List<?> list)) {
                return List.of();
            }
            List<SectionRecord> result = new ArrayList<>();
            for (Object sectionObj : list) {
                if (!(sectionObj instanceof Map<?, ?> sectionMap)) continue;
                Object typeObj = sectionMap.get("type");
                String type = typeObj == null ? "GENERIC" : String.valueOf(typeObj);
                Object titleObj = sectionMap.get("title");
                String title = titleObj == null ? type : String.valueOf(titleObj);
                List<ItemRecord> items = new ArrayList<>();
                Object itemsObj = sectionMap.get("items");
                if (itemsObj instanceof List<?> itemList) {
                    for (Object itemObj : itemList) {
                        if (!(itemObj instanceof Map<?, ?> itemMap)) continue;
                        Object textObj = itemMap.get("text");
                        String text = textObj == null ? "" : String.valueOf(textObj);
                        List<EvidenceRecord> evidence = new ArrayList<>();
                        Object evObj = itemMap.get("evidence");
                        if (evObj instanceof List<?> evList) {
                            for (Object evItem : evList) {
                                if (!(evItem instanceof Map<?, ?> evMap)) continue;
                                evidence.add(new EvidenceRecord(
                                    asString(evMap.get("segmentId")),
                                    asLong(evMap.get("startMs")),
                                    asLong(evMap.get("endMs")),
                                    asString(evMap.get("evidenceTextSnapshot"))
                                ));
                            }
                        }
                        items.add(new ItemRecord(text, evidence));
                    }
                }
                result.add(new SectionRecord(type, title, items));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse minutes sections JSON", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
