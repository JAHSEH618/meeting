package com.meeting.api.domain.llm;

import java.util.Optional;

/**
 * Repository for versioned prompt templates ({@code prompt_templates} table).
 * Tenant-aware: a {@code null} tenant_id row is a system template fallback.
 */
public interface PromptTemplateRepository {
    Optional<PromptTemplate> findActiveByTaskName(String tenantId, String taskName);

    record PromptTemplate(
        String id,
        String tenantId,
        String taskName,
        String version,
        String templateBody,
        String jsonSchema,
        String status,
        String systemPrompt,
        String modelParams
    ) {
        public PromptTemplate(
            String id,
            String tenantId,
            String taskName,
            String version,
            String templateBody,
            String jsonSchema,
            String status
        ) {
            this(id, tenantId, taskName, version, templateBody, jsonSchema, status, null, "{}");
        }
    }
}
