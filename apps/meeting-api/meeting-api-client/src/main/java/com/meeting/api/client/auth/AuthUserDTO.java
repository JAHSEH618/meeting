package com.meeting.api.client.auth;

import java.util.List;

public record AuthUserDTO(
    String userId,
    String tenantId,
    String personId,
    String displayName,
    List<String> roles,
    List<String> permissions
) {
}
