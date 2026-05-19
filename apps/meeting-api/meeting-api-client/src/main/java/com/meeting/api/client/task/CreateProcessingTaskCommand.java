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
    String traceId,
    boolean holdAtWorkerPhase
) {
    /** Convenience constructor for legacy callers that don't yet set the workstation hold flag. */
    public CreateProcessingTaskCommand(
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
        this(tenantId, meetingId, taskType, options, expectedInputVersion,
            requestedBy, idempotencyKey, requestId, traceId, false);
    }
}
