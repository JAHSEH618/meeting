package com.meeting.api.client.person;

import java.time.OffsetDateTime;

public record PersonDTO(
    String personId,
    String displayName,
    String email,
    String externalId,
    OffsetDateTime createdAt
) {
}
