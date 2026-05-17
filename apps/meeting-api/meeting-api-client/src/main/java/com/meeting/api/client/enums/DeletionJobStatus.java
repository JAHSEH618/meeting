package com.meeting.api.client.enums;

/**
 * Deletion job lifecycle — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum DeletionJobStatus {
    REQUESTED,
    PENDING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED,
    BLOCKED_BY_LEGAL_HOLD;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == PARTIAL_FAILED
            || this == FAILED || this == BLOCKED_BY_LEGAL_HOLD;
    }
}
