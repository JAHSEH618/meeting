package com.meeting.api.client.enums;

/**
 * Scopes that a legal hold can protect. Single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum LegalHoldScopeType {
    MEETING,
    DOCUMENT,
    SPEAKER_PROFILE,
    PROJECT
}
