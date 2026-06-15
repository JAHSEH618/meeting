package com.meeting.api.client.auth;

import java.time.OffsetDateTime;

public record RefreshResultDTO(
    String accessToken,
    OffsetDateTime expiresAt
) {}
