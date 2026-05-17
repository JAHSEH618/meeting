package com.meeting.api.client.enums;

/**
 * Business-level result of an audited operation — distinct from
 * raw HTTP status. Single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum AuditResult {
    SUCCESS,
    FAILURE,
    BLOCKED,
    DENIED
}
