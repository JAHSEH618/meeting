package com.meeting.api.client.task;

public record RetryTaskCommand(
    String tenantId,
    String taskId,
    String requestedBy,
    String reason,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
