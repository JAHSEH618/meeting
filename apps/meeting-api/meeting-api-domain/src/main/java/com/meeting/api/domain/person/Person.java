package com.meeting.api.domain.person;

import java.time.OffsetDateTime;

public record Person(
    String id,
    String tenantId,
    String displayName,
    String email,
    String externalRef,
    String status,
    OffsetDateTime createdAt
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
