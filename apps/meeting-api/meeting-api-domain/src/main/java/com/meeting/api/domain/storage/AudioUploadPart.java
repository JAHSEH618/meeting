package com.meeting.api.domain.storage;

import com.meeting.api.client.enums.AudioUploadStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

public record AudioUploadPart(
    String id,
    String tenantId,
    String uploadId,
    String meetingId,
    int partNumber,
    String partSha256,
    long sizeBytes,
    String etag,
    AudioUploadStatus uploadStatus,
    OffsetDateTime uploadedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public AudioUploadPart {
        requireText(id, "id");
        requireText(tenantId, "tenantId");
        requireText(uploadId, "uploadId");
        requireText(meetingId, "meetingId");
        if (partNumber < 1 || partNumber > AudioUploadSession.MAX_PART_COUNT) {
            throw new IllegalArgumentException("partNumber must be between 1 and " + AudioUploadSession.MAX_PART_COUNT);
        }
        requireSha256(partSha256, "partSha256");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        Objects.requireNonNull(uploadStatus, "uploadStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static AudioUploadPart requested(
        String id,
        String tenantId,
        String uploadId,
        String meetingId,
        int partNumber,
        String partSha256,
        long sizeBytes,
        OffsetDateTime now
    ) {
        return new AudioUploadPart(
            id,
            tenantId,
            uploadId,
            meetingId,
            partNumber,
            partSha256,
            sizeBytes,
            null,
            AudioUploadStatus.UPLOADING,
            null,
            now,
            now
        );
    }

    public AudioUploadPart markCompleted(String etag, OffsetDateTime uploadedAt) {
        requireText(etag, "etag");
        return new AudioUploadPart(
            id,
            tenantId,
            uploadId,
            meetingId,
            partNumber,
            partSha256,
            sizeBytes,
            etag,
            AudioUploadStatus.COMPLETED,
            uploadedAt,
            createdAt,
            uploadedAt
        );
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase sha256 hex string");
        }
    }
}
