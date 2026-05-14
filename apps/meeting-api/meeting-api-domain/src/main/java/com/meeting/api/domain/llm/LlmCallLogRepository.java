package com.meeting.api.domain.llm;

import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;

/**
 * Audit log for every LLM provider call (success or failure).
 * Backed by {@code llm_call_logs} table.
 */
public interface LlmCallLogRepository {
    String record(LlmCallLogRecord record);

    record LlmCallLogRecord(
        String id,
        String tenantId,
        String meetingId,
        String taskId,
        String capability,
        String provider,
        String configuredModel,
        String actualModelVersion,
        String promptTemplateId,
        String promptTemplateVersion,
        SecurityLevel securityLevel,
        String inputHash,
        String outputHash,
        Integer tokenInput,
        Integer tokenOutput,
        Integer tokenTotal,
        Integer latencyMs,
        String status,
        String errorCode,
        OffsetDateTime occurredAt
    ) {
    }
}
