package com.meeting.api.domain.storage;

import com.meeting.api.client.enums.AudioUploadStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class AudioUploadSession {
    public static final int DEFAULT_PART_SIZE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PART_COUNT = 10000;
    public static final int TTL_HOURS = 24;

    private final String uploadId;
    private final String tenantId;
    private final String meetingId;
    private final String fileId;
    private final String objectKey;
    private final String bucket;
    private final String contentType;
    private final String fileName;
    private final long fileSizeBytes;
    private final String fileSha256;
    private final int partSizeBytes;
    private final int maxPartCount;
    private final AudioUploadStatus uploadStatus;
    private final String createdBy;
    private final OffsetDateTime expiresAt;
    private final OffsetDateTime completedAt;
    private final OffsetDateTime abortedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    private AudioUploadSession(Builder builder) {
        this.uploadId = requireText(builder.uploadId, "uploadId");
        this.tenantId = requireText(builder.tenantId, "tenantId");
        this.meetingId = requireText(builder.meetingId, "meetingId");
        this.fileId = builder.fileId;
        this.objectKey = requireText(builder.objectKey, "objectKey");
        this.bucket = requireText(builder.bucket, "bucket");
        this.contentType = requireText(builder.contentType, "contentType");
        this.fileName = requireText(builder.fileName, "fileName");
        if (builder.fileSizeBytes <= 0) {
            throw new IllegalArgumentException("fileSizeBytes must be positive");
        }
        this.fileSizeBytes = builder.fileSizeBytes;
        this.fileSha256 = requireSha256(builder.fileSha256, "fileSha256");
        this.partSizeBytes = builder.partSizeBytes == null ? DEFAULT_PART_SIZE_BYTES : builder.partSizeBytes;
        if (this.partSizeBytes < 5 * 1024 * 1024) {
            throw new IllegalArgumentException("partSizeBytes must be at least 5 MiB");
        }
        this.maxPartCount = builder.maxPartCount == null ? MAX_PART_COUNT : builder.maxPartCount;
        if (this.maxPartCount < 1 || this.maxPartCount > MAX_PART_COUNT) {
            throw new IllegalArgumentException("maxPartCount must be between 1 and " + MAX_PART_COUNT);
        }
        this.uploadStatus = Objects.requireNonNull(builder.uploadStatus, "uploadStatus");
        this.createdBy = builder.createdBy;
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt");
        this.completedAt = builder.completedAt;
        this.abortedAt = builder.abortedAt;
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(builder.updatedAt, "updatedAt");
    }

    public static AudioUploadSession create(
        String uploadId,
        String tenantId,
        String meetingId,
        String objectKey,
        String bucket,
        String contentType,
        String fileName,
        long fileSizeBytes,
        String fileSha256,
        Integer partSizeBytes,
        String createdBy,
        OffsetDateTime now
    ) {
        return new Builder()
            .uploadId(uploadId)
            .tenantId(tenantId)
            .meetingId(meetingId)
            .objectKey(objectKey)
            .bucket(bucket)
            .contentType(contentType)
            .fileName(fileName)
            .fileSizeBytes(fileSizeBytes)
            .fileSha256(fileSha256)
            .partSizeBytes(partSizeBytes == null ? DEFAULT_PART_SIZE_BYTES : partSizeBytes)
            .maxPartCount(MAX_PART_COUNT)
            .uploadStatus(AudioUploadStatus.INITIATED)
            .createdBy(createdBy)
            .expiresAt(now.plusHours(TTL_HOURS))
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    public boolean isExpired(OffsetDateTime now) {
        return uploadStatus != AudioUploadStatus.COMPLETED
            && uploadStatus != AudioUploadStatus.ABORTED
            && !expiresAt.isAfter(now);
    }

    public AudioUploadSession markUploading(OffsetDateTime now) {
        if (uploadStatus == AudioUploadStatus.UPLOADING) {
            return this;
        }
        return copy(AudioUploadStatus.UPLOADING, fileId, completedAt, abortedAt, now);
    }

    public AudioUploadSession markCompleted(String fileId, OffsetDateTime now) {
        return copy(AudioUploadStatus.COMPLETED, requireText(fileId, "fileId"), now, abortedAt, now);
    }

    public AudioUploadSession markAborted(OffsetDateTime now) {
        if (uploadStatus == AudioUploadStatus.ABORTED) {
            return this;
        }
        return copy(AudioUploadStatus.ABORTED, fileId, completedAt, now, now);
    }

    public AudioUploadSession markExpired(OffsetDateTime now) {
        if (uploadStatus == AudioUploadStatus.EXPIRED) {
            return this;
        }
        return copy(AudioUploadStatus.EXPIRED, fileId, completedAt, abortedAt, now);
    }

    private AudioUploadSession copy(
        AudioUploadStatus nextStatus,
        String nextFileId,
        OffsetDateTime nextCompletedAt,
        OffsetDateTime nextAbortedAt,
        OffsetDateTime nextUpdatedAt
    ) {
        return new Builder()
            .uploadId(uploadId)
            .tenantId(tenantId)
            .meetingId(meetingId)
            .fileId(nextFileId)
            .objectKey(objectKey)
            .bucket(bucket)
            .contentType(contentType)
            .fileName(fileName)
            .fileSizeBytes(fileSizeBytes)
            .fileSha256(fileSha256)
            .partSizeBytes(partSizeBytes)
            .maxPartCount(maxPartCount)
            .uploadStatus(nextStatus)
            .createdBy(createdBy)
            .expiresAt(expiresAt)
            .completedAt(nextCompletedAt)
            .abortedAt(nextAbortedAt)
            .createdAt(createdAt)
            .updatedAt(nextUpdatedAt)
            .build();
    }

    public String uploadId() { return uploadId; }
    public String tenantId() { return tenantId; }
    public String meetingId() { return meetingId; }
    public String fileId() { return fileId; }
    public String objectKey() { return objectKey; }
    public String bucket() { return bucket; }
    public String contentType() { return contentType; }
    public String fileName() { return fileName; }
    public long fileSizeBytes() { return fileSizeBytes; }
    public String fileSha256() { return fileSha256; }
    public int partSizeBytes() { return partSizeBytes; }
    public int maxPartCount() { return maxPartCount; }
    public AudioUploadStatus uploadStatus() { return uploadStatus; }
    public String createdBy() { return createdBy; }
    public OffsetDateTime expiresAt() { return expiresAt; }
    public OffsetDateTime completedAt() { return completedAt; }
    public OffsetDateTime abortedAt() { return abortedAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase sha256 hex string");
        }
        return value;
    }

    public static final class Builder {
        private String uploadId;
        private String tenantId;
        private String meetingId;
        private String fileId;
        private String objectKey;
        private String bucket;
        private String contentType;
        private String fileName;
        private long fileSizeBytes;
        private String fileSha256;
        private Integer partSizeBytes;
        private Integer maxPartCount;
        private AudioUploadStatus uploadStatus;
        private String createdBy;
        private OffsetDateTime expiresAt;
        private OffsetDateTime completedAt;
        private OffsetDateTime abortedAt;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        public Builder uploadId(String v) { this.uploadId = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder meetingId(String v) { this.meetingId = v; return this; }
        public Builder fileId(String v) { this.fileId = v; return this; }
        public Builder objectKey(String v) { this.objectKey = v; return this; }
        public Builder bucket(String v) { this.bucket = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder fileName(String v) { this.fileName = v; return this; }
        public Builder fileSizeBytes(long v) { this.fileSizeBytes = v; return this; }
        public Builder fileSha256(String v) { this.fileSha256 = v; return this; }
        public Builder partSizeBytes(Integer v) { this.partSizeBytes = v; return this; }
        public Builder maxPartCount(Integer v) { this.maxPartCount = v; return this; }
        public Builder uploadStatus(AudioUploadStatus v) { this.uploadStatus = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder expiresAt(OffsetDateTime v) { this.expiresAt = v; return this; }
        public Builder completedAt(OffsetDateTime v) { this.completedAt = v; return this; }
        public Builder abortedAt(OffsetDateTime v) { this.abortedAt = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(OffsetDateTime v) { this.updatedAt = v; return this; }

        public AudioUploadSession build() {
            return new AudioUploadSession(this);
        }
    }
}
