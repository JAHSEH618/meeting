package com.meeting.api.infrastructure.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantSessionContext {
    private final JdbcTemplate jdbcTemplate;

    public TenantSessionContext(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void set(String tenantId, String userId, String requestId) {
        setConfig("app.tenant_id", tenantId);
        setConfig("app.user_id", userId);
        setConfig("app.request_id", requestId);
    }

    public void reset() {
        jdbcTemplate.execute("RESET app.tenant_id");
        jdbcTemplate.execute("RESET app.user_id");
        jdbcTemplate.execute("RESET app.request_id");
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("SELECT set_config(?, ?, true)", key, value == null ? "" : value);
    }
}
