package com.meeting.api.infrastructure.persistence.llm;

import com.meeting.api.domain.llm.PromptTemplateRepository;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPromptTemplateRepository implements PromptTemplateRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcPromptTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PromptTemplate> findActiveByTaskName(String tenantId, String taskName) {
        String sql = """
            SELECT id, tenant_id, task_name, version, template_body, json_schema::text AS json_schema, status
              FROM prompt_templates
             WHERE task_name = ?
               AND status = 'ACTIVE'
               AND (tenant_id IS NOT DISTINCT FROM ?)
             ORDER BY major_version DESC, minor_version DESC, patch_version DESC, updated_at DESC
             LIMIT 1
            """;
        return jdbcTemplate.query(
            sql,
            rs -> rs.next() ? Optional.of(new PromptTemplate(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("task_name"),
                rs.getString("version"),
                rs.getString("template_body"),
                rs.getString("json_schema"),
                rs.getString("status")
            )) : Optional.<PromptTemplate>empty(),
            taskName,
            tenantId
        );
    }
}
