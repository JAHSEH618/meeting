package com.meeting.api.client.task;

/** Command issued by the workstation finalize step (P9 D3). */
public record ResumeJavaPhaseCommand(
    String tenantId,
    String taskId,
    String requestedBy,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
