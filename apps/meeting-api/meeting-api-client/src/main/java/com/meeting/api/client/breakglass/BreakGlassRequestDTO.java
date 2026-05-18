package com.meeting.api.client.breakglass;

import com.meeting.api.client.enums.BreakGlassStatus;
import java.time.OffsetDateTime;

public record BreakGlassRequestDTO(
    String breakGlassRequestId,
    String requesterId,
    String scopeType,
    String scopeId,
    String reason,
    BreakGlassStatus status,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String approverId,
    OffsetDateTime approvedAt,
    OffsetDateTime rejectedAt,
    String rejectReason,
    OffsetDateTime revokedAt,
    String revokedBy,
    OffsetDateTime createdAt
) {}
