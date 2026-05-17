package com.meeting.api.infrastructure.persistence.compliance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionJob;
import com.meeting.api.domain.compliance.DeletionJobRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeletionJobRepository implements DeletionJobRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDeletionJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(DeletionJob job) {
        jdbc.update(
            """
            INSERT INTO deletion_jobs (
              id, tenant_id, scope_type, scope_id, status,
              requested_by, approved_by, legal_hold_checked,
              deleted_rows_json, deleted_files_json, kms_keys_destroyed_json,
              certificate_hash, error_code, created_at, updated_at, finished_at
            ) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?)
            """,
            job.id(),
            job.tenantId(),
            job.scopeType().name(),
            job.scopeId(),
            job.status().name(),
            job.requestedBy(),
            job.approvedBy(),
            job.legalHoldChecked(),
            toJson(job.deletedRowsJson()),
            toJson(job.deletedFilesJson()),
            toJson(job.kmsKeysDestroyedJson()),
            job.certificateHash(),
            job.errorCode() == null ? null : job.errorCode().name(),
            ts(job.createdAt()),
            ts(job.updatedAt()),
            ts(job.finishedAt())
        );
    }

    @Override
    public void update(DeletionJob job) {
        jdbc.update(
            """
            UPDATE deletion_jobs SET
              status = ?, legal_hold_checked = ?,
              deleted_rows_json = ?::jsonb,
              deleted_files_json = ?::jsonb,
              kms_keys_destroyed_json = ?::jsonb,
              certificate_hash = ?, error_code = ?,
              updated_at = ?, finished_at = ?
            WHERE tenant_id = ? AND id = ?
            """,
            job.status().name(),
            job.legalHoldChecked(),
            toJson(job.deletedRowsJson()),
            toJson(job.deletedFilesJson()),
            toJson(job.kmsKeysDestroyedJson()),
            job.certificateHash(),
            job.errorCode() == null ? null : job.errorCode().name(),
            ts(job.updatedAt()),
            ts(job.finishedAt()),
            job.tenantId(),
            job.id()
        );
    }

    @Override
    public Optional<DeletionJob> findById(String tenantId, String jobId) {
        List<DeletionJob> rows = jdbc.query(
            "SELECT * FROM deletion_jobs WHERE tenant_id = ? AND id = ?",
            mapper(),
            tenantId, jobId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PageResult<DeletionJob> listByTenant(String tenantId, String cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder("SELECT * FROM deletion_jobs WHERE tenant_id = ?");
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                String createdAtIso = cursor.substring(0, sep);
                String idCursor = cursor.substring(sep + 1);
                sql.append(" AND (created_at, id) < (?, ?)");
                args.add(Timestamp.from(OffsetDateTime.parse(createdAtIso).toInstant()));
                args.add(idCursor);
            }
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(safeLimit + 1);

        List<DeletionJob> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        List<DeletionJob> page = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = null;
        if (hasMore) {
            DeletionJob last = page.get(page.size() - 1);
            nextCursor = last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString()
                + "|" + last.id();
        }
        return new PageResult<>(page, new PageResult.PageInfo(nextCursor, hasMore, safeLimit));
    }

    @Override
    public List<DeletionJob> claimByStatus(String tenantId, DeletionJobStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
            """
            SELECT * FROM deletion_jobs
            WHERE tenant_id = ? AND status = ?
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            mapper(),
            tenantId, status.name(), safeLimit
        );
    }

    private RowMapper<DeletionJob> mapper() {
        return (rs, rowNum) -> DeletionJob.builder()
            .id(rs.getString("id"))
            .tenantId(rs.getString("tenant_id"))
            .scopeType(DeletionScopeType.valueOf(rs.getString("scope_type")))
            .scopeId(rs.getString("scope_id"))
            .requestedBy(rs.getString("requested_by"))
            .approvedBy(rs.getString("approved_by"))
            .status(DeletionJobStatus.valueOf(rs.getString("status")))
            .legalHoldChecked(rs.getBoolean("legal_hold_checked"))
            .deletedRowsJson(parseJson(rs.getString("deleted_rows_json")))
            .deletedFilesJson(parseJson(rs.getString("deleted_files_json")))
            .kmsKeysDestroyedJson(parseJson(rs.getString("kms_keys_destroyed_json")))
            .certificateHash(rs.getString("certificate_hash"))
            .errorCode(parseErrorCode(rs.getString("error_code")))
            .createdAt(odt(rs, "created_at"))
            .updatedAt(odt(rs, "updated_at"))
            .finishedAt(odt(rs, "finished_at"))
            .build();
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize deletion_jobs json column", ex);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            // Treat malformed payload as empty rather than crashing reads.
            return Map.of();
        }
    }

    private static ErrorCode parseErrorCode(String value) {
        if (value == null) return null;
        try {
            return ErrorCode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return ErrorCode.INTERNAL_ERROR;
        }
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp ts(OffsetDateTime at) {
        return at == null ? null : Timestamp.from(at.toInstant());
    }
}
