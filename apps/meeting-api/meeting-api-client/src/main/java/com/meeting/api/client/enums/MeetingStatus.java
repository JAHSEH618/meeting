package com.meeting.api.client.enums;

/**
 * 会议生命周期状态 — 单一事实来源: packages/meeting-contracts/schemas/common/enums.yaml
 */
public enum MeetingStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    DELETED
}