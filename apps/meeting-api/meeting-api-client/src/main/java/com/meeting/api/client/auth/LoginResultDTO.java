package com.meeting.api.client.auth;

import java.time.OffsetDateTime;

public record LoginResultDTO(
    String accessToken,
    OffsetDateTime expiresAt,
    AuthUserDTO user
) {
}
