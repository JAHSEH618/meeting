package com.meeting.api.client.enums;

/**
 * Export job scope — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum ExportType {
    /** Single-meeting export (phase 1). */
    MEETING,
    /** Compliance audit export (phase 7, reserved). */
    AUDIT
}
