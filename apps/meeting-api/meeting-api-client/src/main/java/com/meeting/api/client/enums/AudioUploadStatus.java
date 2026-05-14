package com.meeting.api.client.enums;

/**
 * Audio upload session lifecycle — single source: packages/meeting-contracts/schemas/common/enums.yaml
 */
public enum AudioUploadStatus {
    INITIATED,
    UPLOADING,
    COMPLETED,
    ABORTED,
    EXPIRED
}
