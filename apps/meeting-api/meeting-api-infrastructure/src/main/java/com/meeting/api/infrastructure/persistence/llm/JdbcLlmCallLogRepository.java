package com.meeting.api.infrastructure.persistence.llm;

import com.meeting.api.domain.llm.LlmCallLogRepository;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLlmCallLogRepository implements LlmCallLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcLlmCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String record(LlmCallLogRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO llm_call_logs (
              id, tenant_id, meeting_id, task_id, capability, provider, configured_model, actual_model_version,
              prompt_template_id, prompt_template_version, input_hash, output_hash,
              token_input, token_output, token_total, latency_ms, status, error_code, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.taskId(),
            record.capability(),
            record.provider(),
            record.configuredModel(),
            record.actualModelVersion(),
            record.promptTemplateId(),
            record.promptTemplateVersion(),
            record.inputHash(),
            record.outputHash(),
            record.tokenInput(),
            record.tokenOutput(),
            record.tokenTotal(),
            record.latencyMs(),
            record.status(),
            record.errorCode(),
            Timestamp.from(record.occurredAt().toInstant())
        );
        return record.id();
    }
}
