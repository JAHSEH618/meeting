package com.meeting.api.client.enums;

/**
 * Scope types accepted by a deletion job — single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum DeletionScopeType {
    MEETING,
    DOCUMENT,
    SPEAKER_PROFILE,
    USER,
    PROJECT,
    TENANT
}
