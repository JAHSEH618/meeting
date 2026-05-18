package com.meeting.api.domain.export;

import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emitted when an {@link ExportJob} is first persisted in QUEUED.
 * The outbox publisher fans this out as an {@code export-queue}
 * RabbitMQ message conforming to
 * {@code packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json}.
 */
public record ExportJobCreatedEvent(
    String eventId,
    String tenantId,
    String exportId,
    String meetingId,
    ExportFormat format,
    int inputTranscriptVersion,
    Integer inputMinutesVersion,
    long sequenceNo,
    OffsetDateTime occurredAt,
    String traceId,
    Map<String, Object> payload
) implements DomainEvent {

    public ExportJobCreatedEvent(
        String eventId,
        String tenantId,
        String exportId,
        String meetingId,
        ExportFormat format,
        int inputTranscriptVersion,
        Integer inputMinutesVersion,
        long sequenceNo,
        OffsetDateTime occurredAt
    ) {
        this(eventId, tenantId, exportId, meetingId, format,
             inputTranscriptVersion, inputMinutesVersion, sequenceNo, occurredAt,
             /* traceId */ eventId);
    }

    public ExportJobCreatedEvent(
        String eventId,
        String tenantId,
        String exportId,
        String meetingId,
        ExportFormat format,
        int inputTranscriptVersion,
        Integer inputMinutesVersion,
        long sequenceNo,
        OffsetDateTime occurredAt,
        String traceId
    ) {
        this(
            eventId, tenantId, exportId, meetingId, format,
            inputTranscriptVersion, inputMinutesVersion, sequenceNo, occurredAt,
            traceId == null || traceId.isBlank() ? eventId : traceId,
            buildPayload(
                tenantId, exportId, meetingId, format,
                inputTranscriptVersion, inputMinutesVersion,
                traceId == null || traceId.isBlank() ? eventId : traceId,
                occurredAt
            )
        );
    }

    private static Map<String, Object> buildPayload(
        String tenantId,
        String exportId,
        String meetingId,
        ExportFormat format,
        int inputTranscriptVersion,
        Integer inputMinutesVersion,
        String traceId,
        OffsetDateTime occurredAt
    ) {
        Map<String, Object> expectedInputVersion = new LinkedHashMap<>();
        expectedInputVersion.put("transcriptVersion", inputTranscriptVersion);
        if (inputMinutesVersion != null) {
            expectedInputVersion.put("minutesVersion", inputMinutesVersion);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("exportId", exportId);
        body.put("meetingId", meetingId);
        body.put("format", format.name());
        body.put("expectedInputVersion", expectedInputVersion);
        body.put("traceId", traceId);
        body.put("createdAt", occurredAt == null ? null : occurredAt.toString());
        return body;
    }

    @Override
    public String eventType() {
        return "ExportJobCreatedEvent";
    }

    @Override
    public String aggregateType() {
        return "ExportJob";
    }

    @Override
    public String aggregateId() {
        return exportId;
    }

    @Override
    public String payloadVersion() {
        return "v1";
    }
}
