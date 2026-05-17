package com.meeting.api.client.enums;

/**
 * Export job lifecycle status — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum ExportStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REVOKED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == REVOKED;
    }
}
