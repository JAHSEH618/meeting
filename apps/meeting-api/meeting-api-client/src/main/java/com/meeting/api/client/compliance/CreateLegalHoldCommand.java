package com.meeting.api.client.compliance;

import com.meeting.api.client.enums.LegalHoldScopeType;
import java.util.Objects;

/** Command for {@code POST /api/legal-holds}. */
public record CreateLegalHoldCommand(
    String tenantId,
    LegalHoldScopeType scopeType,
    String scopeId,
    String reason,
    String requestedBy,
    String approvedBy,
    String requestId,
    String traceId
) {

    public CreateLegalHoldCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedBy, "requestedBy");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (scopeId.isBlank()) throw new IllegalArgumentException("scopeId must not be blank");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (requestedBy.isBlank()) throw new IllegalArgumentException("requestedBy must not be blank");
    }
}
