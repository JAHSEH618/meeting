package com.meeting.api.client.task;

import java.util.Map;

public record CreateProcessingTaskCommand(
    String tenantId,
    String meetingId,
    String taskType,
    Map<String, Object> options,
    Map<String, Object> expectedInputVersion,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
