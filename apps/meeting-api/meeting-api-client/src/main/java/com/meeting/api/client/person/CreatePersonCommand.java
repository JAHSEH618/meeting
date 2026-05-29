package com.meeting.api.client.person;

public record CreatePersonCommand(
    String tenantId,
    String displayName,
    String email,
    String externalId,
    boolean forceCreate,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
