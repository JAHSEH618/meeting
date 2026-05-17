package com.meeting.api.domain.export;

import com.meeting.api.client.enums.ExportStatus;
import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emitted when an {@link ExportJob} reaches a terminal status
 * ({@link ExportStatus#SUCCEEDED}, {@link ExportStatus#FAILED} or
 * {@link ExportStatus#CANCELLED}). The SSE emitter projects this into
 * the {@code EXPORT_STATUS_CHANGED} stream.
 */
public record ExportJobCompletedEvent(
    String eventId,
    String tenantId,
    String exportId,
    String meetingId,
    ExportStatus status,
    String fileId,
    String fileHash,
    String errorCode,
    long sequenceNo,
    OffsetDateTime occurredAt,
    Map<String, Object> payload
) implements DomainEvent {

    public ExportJobCompletedEvent(
        String eventId,
        String tenantId,
        String exportId,
        String meetingId,
        ExportStatus status,
        String fileId,
        String fileHash,
        String errorCode,
        long sequenceNo,
        OffsetDateTime occurredAt
    ) {
        this(
            eventId, tenantId, exportId, meetingId, status,
            fileId, fileHash, errorCode, sequenceNo, occurredAt,
            buildPayload(exportId, meetingId, status, fileId, fileHash, errorCode)
        );
    }

    private static Map<String, Object> buildPayload(
        String exportId,
        String meetingId,
        ExportStatus status,
        String fileId,
        String fileHash,
        String errorCode
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportId", exportId);
        body.put("meetingId", meetingId);
        body.put("status", status.name());
        if (fileId != null) body.put("fileId", fileId);
        if (fileHash != null) body.put("fileHash", fileHash);
        if (errorCode != null) body.put("errorCode", errorCode);
        return body;
    }

    @Override
    public String eventType() {
        return "ExportJobCompletedEvent";
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
