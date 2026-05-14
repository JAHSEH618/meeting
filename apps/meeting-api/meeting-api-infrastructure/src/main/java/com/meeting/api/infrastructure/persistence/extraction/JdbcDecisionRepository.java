package com.meeting.api.infrastructure.persistence.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.domain.extraction.ActionItemRepository.EvidenceJson;
import com.meeting.api.domain.extraction.DecisionRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDecisionRepository implements DecisionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDecisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String save(DecisionRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO meeting_decisions (
              id, tenant_id, meeting_id, title, description, status, acceptance_status,
              source_transcript_version, stale_status, evidence_segment_ids, evidence_json,
              artifact_manifest_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::stale_status, ?::jsonb, ?::jsonb, ?, ?, ?)
            """,
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.title(),
            record.description(),
            record.status(),
            record.acceptanceStatus(),
            record.sourceTranscriptVersion(),
            record.staleStatus().name(),
            serializeSegmentIds(record.evidence()),
            serializeEvidence(record.evidence()),
            record.artifactManifestId(),
            Timestamp.from(record.createdAt().toInstant()),
            Timestamp.from(record.updatedAt().toInstant())
        );
        return record.id();
    }

    @Override
    public List<DecisionRecord> findByMeeting(String tenantId, String meetingId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, title, description, status, acceptance_status,
                   source_transcript_version, stale_status::text AS stale_status,
                   evidence_json::text AS evidence_json, artifact_manifest_id, created_at, updated_at
              FROM meeting_decisions
             WHERE tenant_id = ? AND meeting_id = ?
             ORDER BY created_at ASC
            """,
            (rs, n) -> new DecisionRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("meeting_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("acceptance_status"),
                (Integer) rs.getObject("source_transcript_version"),
                StaleStatus.valueOf(rs.getString("stale_status")),
                parseEvidence(rs.getString("evidence_json")),
                rs.getString("artifact_manifest_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
            ),
            tenantId,
            meetingId
        );
    }

    @Override
    public void markAcceptance(String tenantId, String id, String acceptanceStatus, String userId, OffsetDateTime now) {
        jdbcTemplate.update(
            "UPDATE meeting_decisions SET acceptance_status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
            acceptanceStatus,
            Timestamp.from(now.toInstant()),
            tenantId,
            id
        );
    }

    @Override
    public void markStaleForMeeting(String tenantId, String meetingId) {
        jdbcTemplate.update(
            "UPDATE meeting_decisions SET stale_status = 'STALE'::stale_status, updated_at = now()"
                + " WHERE tenant_id = ? AND meeting_id = ? AND stale_status = 'ACTIVE'",
            tenantId,
            meetingId
        );
    }

    private String serializeSegmentIds(List<EvidenceJson> evidence) {
        try {
            List<String> ids = new ArrayList<>();
            for (var e : evidence) if (e.segmentId() != null) ids.add(e.segmentId());
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String serializeEvidence(List<EvidenceJson> evidence) {
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (var e : evidence) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("segmentId", e.segmentId());
                map.put("startMs", e.startMs());
                map.put("endMs", e.endMs());
                map.put("evidenceTextSnapshot", e.evidenceTextSnapshot());
                payload.add(map);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<EvidenceJson> parseEvidence(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<?> list = objectMapper.readValue(json, List.class);
            List<EvidenceJson> result = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                result.add(new EvidenceJson(
                    m.get("segmentId") == null ? null : String.valueOf(m.get("segmentId")),
                    asLong(m.get("startMs")),
                    asLong(m.get("endMs")),
                    m.get("evidenceTextSnapshot") == null ? null : String.valueOf(m.get("evidenceTextSnapshot"))
                ));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
