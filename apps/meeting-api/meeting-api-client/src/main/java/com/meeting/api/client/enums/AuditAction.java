package com.meeting.api.client.enums;

/**
 * Audited action verbs — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum AuditAction {
    CREATE,
    READ,
    UPDATE,
    DELETE,
    EXPORT,
    LOGIN,
    LOGOUT,
    LEGAL_HOLD_PLACE,
    LEGAL_HOLD_RELEASE,
    DELETION_REQUEST,
    DELETION_EXECUTE,
    BREAK_GLASS_REQUEST,
    BREAK_GLASS_APPROVE,
    BREAK_GLASS_REJECT,
    BREAK_GLASS_ACCESS
}
