package com.meeting.api.client.breakglass;

import java.util.Objects;

/** Command for {@code POST /admin/break-glass/requests}. */
public record CreateBreakGlassCommand(
    String tenantId,
    String scopeType,
    String scopeId,
    String reason,
    String requesterId,
    String requestId,
    String traceId
) {

    public CreateBreakGlassCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requesterId, "requesterId");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (scopeType.isBlank()) throw new IllegalArgumentException("scopeType must not be blank");
        if (scopeId.isBlank()) throw new IllegalArgumentException("scopeId must not be blank");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (requesterId.isBlank()) throw new IllegalArgumentException("requesterId must not be blank");
    }
}
