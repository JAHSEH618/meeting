package com.meeting.api.infrastructure.persistence.task;

import com.meeting.api.domain.task.CallbackNonceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class JdbcCallbackNonceRepository implements CallbackNonceRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCallbackNonceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean exists(String tenantId, String nonce) {
        String sql = """
            SELECT COUNT(*) FROM callback_nonces
            WHERE tenant_id = ? AND nonce = ?
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tenantId, nonce);
        return count != null && count > 0;
    }

    @Override
    public boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName) {
        String sql = """
            INSERT INTO callback_nonces (tenant_id, nonce, worker_id, task_id, step_name)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, nonce) DO NOTHING
            """;
        int rows = jdbcTemplate.update(sql, tenantId, nonce, workerId, taskId, stepName);
        return rows > 0;
    }

    @Override
    public int cleanupExpired(OffsetDateTime before) {
        String sql = """
            DELETE FROM callback_nonces
            WHERE expires_at < ?
            """;
        return jdbcTemplate.update(sql, before);
    }
}
