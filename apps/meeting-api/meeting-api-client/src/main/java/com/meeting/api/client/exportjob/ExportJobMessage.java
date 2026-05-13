package com.meeting.api.client.exportjob;

import java.time.OffsetDateTime;

/**
 * RabbitMQ export-queue message payload.
 * <p>
 * Source of truth: {@code schemas/rabbitmq/export-job-message.schema.json}
 * in {@code packages/meeting-contracts}. Hand-written in phase 0; future
 * phases may switch to jsonschema2pojo generation.
 *
 * @see <a href="https://github.com/meeting-local/meeting/blob/main/packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json">export-job-message.schema.json</a>
 */
public record ExportJobMessage(
    String tenantId,
    String meetingId,
    String exportId,
    ExportFormat format,
    ExpectedInputVersion expectedInputVersion,
    String traceId,
    OffsetDateTime createdAt
) {

    /**
     * Version vector that the export job expects for its input artifacts.
     */
    public record ExpectedInputVersion(
        int transcriptVersion,
        Integer minutesVersion,
        Integer ragVersion
    ) {
    }

    /**
     * Export format enum — must stay in sync with
     * {@code schemas/common/enums.yaml :: ExportFormat}.
     */
    public enum ExportFormat {
        MARKDOWN,
        DOCX,
        PDF
    }
}
