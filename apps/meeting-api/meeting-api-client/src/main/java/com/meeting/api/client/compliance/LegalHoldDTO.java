package com.meeting.api.client.compliance;

import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.client.enums.LegalHoldStatus;
import java.time.OffsetDateTime;

/** DTO returned by {@code GET / POST / PUT /api/legal-holds*}. */
public record LegalHoldDTO(
    String legalHoldId,
    LegalHoldScopeType scopeType,
    String scopeId,
    String reason,
    LegalHoldStatus status,
    String requestedBy,
    String approvedBy,
    OffsetDateTime createdAt,
    OffsetDateTime releasedAt,
    String releasedBy,
    String releaseReason
) {}
