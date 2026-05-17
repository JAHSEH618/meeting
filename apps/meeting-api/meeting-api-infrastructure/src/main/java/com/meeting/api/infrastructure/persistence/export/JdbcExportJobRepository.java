package com.meeting.api.infrastructure.persistence.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.ExportDataBoundaryMode;
import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.client.enums.ExportType;
import com.meeting.api.client.export.ExportRenderOptions;
import com.meeting.api.domain.export.ExportJob;
import com.meeting.api.domain.export.ExportJobRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ExportJobRepository}. Relies on RLS for
 * tenant isolation — every transaction must have {@code app.tenant_id}
 * set via {@code TenantScopedTransaction} before any of these queries
 * fire, otherwise the policy denies all rows.
 *
 * <p>Cursor format for {@link #listByMeeting} is {@code <createdAtIso>|<id>}
 * so we can sort stably by {@code (created_at DESC, id DESC)}.
 */
@Repository
public class JdbcExportJobRepository implements ExportJobRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcExportJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(ExportJob job) {
        jdbc.update(
            """
            INSERT INTO export_jobs (
              id, tenant_id, meeting_id, export_type, format, data_boundary_mode,
              status, input_minutes_version, input_transcript_version,
              snapshot_manifest_id, watermark_text, render_options_json,
              file_id, file_hash, download_expires_at, download_revoked_at,
              error_code, created_by, created_at, updated_at, finished_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?)
            """,
            job.id(),
            job.tenantId(),
            job.meetingId(),
            job.exportType().name(),
            job.format().name(),
            job.dataBoundaryMode().name(),
            job.status().name(),
            job.inputMinutesVersion(),
            job.inputTranscriptVersion(),
            job.snapshotManifestId(),
            job.watermarkText(),
            renderOptionsJson(job.renderOptions()),
            job.fileId(),
            job.fileHash(),
            ts(job.downloadExpiresAt()),
            ts(job.downloadRevokedAt()),
            job.errorCode() == null ? null : job.errorCode().name(),
            job.createdBy(),
            ts(job.createdAt()),
            ts(job.updatedAt()),
            ts(job.finishedAt())
        );
    }

    @Override
    public void update(ExportJob job) {
        jdbc.update(
            """
            UPDATE export_jobs SET
              status = ?,
              file_id = ?,
              file_hash = ?,
              download_expires_at = ?,
              download_revoked_at = ?,
              error_code = ?,
              updated_at = ?,
              finished_at = ?
            WHERE tenant_id = ? AND id = ?
            """,
            job.status().name(),
            job.fileId(),
            job.fileHash(),
            ts(job.downloadExpiresAt()),
            ts(job.downloadRevokedAt()),
            job.errorCode() == null ? null : job.errorCode().name(),
            ts(job.updatedAt()),
            ts(job.finishedAt()),
            job.tenantId(),
            job.id()
        );
    }

    @Override
    public Optional<ExportJob> findById(String tenantId, String exportId) {
        List<ExportJob> rows = jdbc.query(
            """
            SELECT * FROM export_jobs WHERE tenant_id = ? AND id = ?
            """,
            mapper(),
            tenantId, exportId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PageResult<ExportJob> listByMeeting(
        String tenantId, String meetingId, String cursor, int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(meetingId);
        StringBuilder sql = new StringBuilder("""
            SELECT * FROM export_jobs
            WHERE tenant_id = ? AND meeting_id = ?
            """);
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

        List<ExportJob> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        List<ExportJob> page = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = null;
        if (hasMore) {
            ExportJob last = page.get(page.size() - 1);
            nextCursor = last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString()
                + "|" + last.id();
        }
        return new PageResult<>(page, new PageResult.PageInfo(nextCursor, hasMore, safeLimit));
    }

    @Override
    public List<ExportJob> claimByStatus(String tenantId, ExportStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
            """
            SELECT * FROM export_jobs
            WHERE tenant_id = ? AND status = ?
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            mapper(),
            tenantId, status.name(), safeLimit
        );
    }

    private RowMapper<ExportJob> mapper() {
        return (rs, rowNum) -> {
            ExportRenderOptions opts;
            try {
                opts = readRenderOptions(rs.getString("render_options_json"));
            } catch (JsonProcessingException ex) {
                throw new SQLException("malformed render_options_json", ex);
            }
            return ExportJob.builder()
                .id(rs.getString("id"))
                .tenantId(rs.getString("tenant_id"))
                .meetingId(rs.getString("meeting_id"))
                .exportType(ExportType.valueOf(rs.getString("export_type")))
                .format(ExportFormat.valueOf(rs.getString("format")))
                .dataBoundaryMode(parseBoundary(rs.getString("data_boundary_mode")))
                .status(ExportStatus.valueOf(rs.getString("status")))
                .inputMinutesVersion(getNullableInt(rs, "input_minutes_version"))
                .inputTranscriptVersion(getNullableInt(rs, "input_transcript_version"))
                .snapshotManifestId(rs.getString("snapshot_manifest_id"))
                .watermarkText(rs.getString("watermark_text"))
                .renderOptions(opts)
                .fileId(rs.getString("file_id"))
                .fileHash(rs.getString("file_hash"))
                .downloadExpiresAt(odt(rs, "download_expires_at"))
                .downloadRevokedAt(odt(rs, "download_revoked_at"))
                .errorCode(parseErrorCode(rs.getString("error_code")))
                .createdBy(rs.getString("created_by"))
                .createdAt(odt(rs, "created_at"))
                .updatedAt(odt(rs, "updated_at"))
                .finishedAt(odt(rs, "finished_at"))
                .build();
        };
    }

    private ExportRenderOptions readRenderOptions(String json) throws JsonProcessingException {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return ExportRenderOptions.defaults();
        }
        return objectMapper.readValue(json, ExportRenderOptions.class);
    }

    private String renderOptionsJson(ExportRenderOptions opts) {
        try {
            return objectMapper.writeValueAsString(opts);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize ExportRenderOptions", ex);
        }
    }

    private static ExportDataBoundaryMode parseBoundary(String value) {
        return value == null ? ExportDataBoundaryMode.FULL : ExportDataBoundaryMode.valueOf(value);
    }

    private static ErrorCode parseErrorCode(String value) {
        if (value == null) return null;
        try {
            return ErrorCode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return ErrorCode.INTERNAL_ERROR;
        }
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp ts(OffsetDateTime at) {
        return at == null ? null : Timestamp.from(at.toInstant());
    }
}
