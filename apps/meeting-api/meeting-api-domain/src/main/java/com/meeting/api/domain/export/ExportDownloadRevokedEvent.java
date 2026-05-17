package com.meeting.api.domain.export;

import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emitted when a previously-{@code SUCCEEDED} export's download link
 * is revoked. Distinct from {@link ExportJobCompletedEvent} so SSE
 * listeners can show a dedicated "link revoked" toast without
 * re-running terminal-state handling.
 */
public record ExportDownloadRevokedEvent(
    String eventId,
    String tenantId,
    String exportId,
    String meetingId,
    String revokedBy,
    long sequenceNo,
    OffsetDateTime occurredAt,
    Map<String, Object> payload
) implements DomainEvent {

    public ExportDownloadRevokedEvent(
        String eventId,
        String tenantId,
        String exportId,
        String meetingId,
        String revokedBy,
        long sequenceNo,
        OffsetDateTime occurredAt
    ) {
        this(
            eventId, tenantId, exportId, meetingId, revokedBy,
            sequenceNo, occurredAt,
            buildPayload(exportId, meetingId, revokedBy)
        );
    }

    private static Map<String, Object> buildPayload(
        String exportId, String meetingId, String revokedBy
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportId", exportId);
        body.put("meetingId", meetingId);
        if (revokedBy != null) body.put("revokedBy", revokedBy);
        return body;
    }

    @Override
    public String eventType() {
        return "ExportDownloadRevokedEvent";
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
