package com.meeting.api.client.enums;

public enum ProcessingTaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    ORPHANED,
    PARTIAL_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    CANCEL_PENDING,
    CANCELLED
}
