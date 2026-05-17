package com.meeting.api.client.enums;

/**
 * Break-glass access request lifecycle — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum BreakGlassStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED;

    public boolean isTerminal() {
        return this == REJECTED || this == EXPIRED || this == REVOKED;
    }
}
