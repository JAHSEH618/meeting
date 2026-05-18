package com.meeting.api.infrastructure.persistence.compliance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionCertificateRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeletionCertificateRepository implements DeletionCertificateRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_STRING_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDeletionCertificateRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(DeletionCertificateRecord record) {
        jdbc.update(
            """
            INSERT INTO deletion_certificates (
              id, tenant_id, deletion_job_id, scope_type, scope_id,
              object_hashes_json, deleted_rows_json, deleted_files_json,
              failed_items_json, certificate_hash, created_at
            ) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)
            """,
            record.id(),
            record.tenantId(),
            record.deletionJobId(),
            record.scopeType().name(),
            record.scopeId(),
            toJson(record.objectHashes()),
            toJson(record.deletedRows()),
            toJson(record.deletedFiles()),
            toJson(record.failedItems()),
            record.certificateHash(),
            Timestamp.from(record.createdAt().toInstant())
        );
    }

    @Override
    public Optional<DeletionCertificateRecord> findByJobId(String tenantId, String deletionJobId) {
        List<DeletionCertificateRecord> rows = jdbc.query(
            """
            SELECT * FROM deletion_certificates
            WHERE tenant_id = ? AND deletion_job_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """,
            mapper(),
            tenantId, deletionJobId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private RowMapper<DeletionCertificateRecord> mapper() {
        return (rs, rowNum) -> new DeletionCertificateRecord(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("deletion_job_id"),
            DeletionScopeType.valueOf(rs.getString("scope_type")),
            rs.getString("scope_id"),
            parseListMap(rs.getString("object_hashes_json")),
            parseMap(rs.getString("deleted_rows_json")),
            parseListMap(rs.getString("deleted_files_json")),
            parseListString(rs.getString("failed_items_json")),
            rs.getString("certificate_hash"),
            odt(rs, "created_at")
        );
    }

    private String toJson(Object value) {
        if (value == null) return value instanceof java.util.Collection<?> ? "[]" : "{}";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize deletion_certificates column", ex);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> parseListMap(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return objectMapper.readValue(json, LIST_MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<String> parseListString(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return objectMapper.readValue(json, LIST_STRING_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
