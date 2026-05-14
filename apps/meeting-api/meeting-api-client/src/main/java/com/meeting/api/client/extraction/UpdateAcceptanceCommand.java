package com.meeting.api.client.extraction;

public record UpdateAcceptanceCommand(
    String tenantId,
    String meetingId,
    String itemId,
    String itemKind,
    String acceptanceStatus,
    String requestedBy,
    String requestId,
    String traceId,
    String idempotencyKey
) {
    public enum ItemKind {
        ACTION_ITEM,
        DECISION,
        RISK
    }
}
