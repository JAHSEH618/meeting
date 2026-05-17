package com.meeting.api.client.enums;

/**
 * Actor type recorded on an audit event. Single source of truth:
 * packages/meeting-contracts/schemas/common/enums.yaml.
 */
public enum AuditActorType {
    USER,
    SYSTEM,
    SERVICE_ACCOUNT
}
