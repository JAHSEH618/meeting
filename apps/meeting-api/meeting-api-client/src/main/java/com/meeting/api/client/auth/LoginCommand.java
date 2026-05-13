package com.meeting.api.client.auth;

public record LoginCommand(
    String username,
    String password,
    String requestId,
    String traceId
) {
}
