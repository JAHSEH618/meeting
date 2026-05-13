package com.meeting.api.client.task;

public record CancelTaskCommand(
    String tenantId,
    String taskId,
    String requestedBy,
    String reason,
    String requestId,
    String traceId
) {
}
