package com.meeting.api.domain.storage;

import java.time.OffsetDateTime;
import java.util.Map;

public interface ObjectStorageGateway {
    String defaultBucket();

    PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt);

    PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt);

    StorageObject statObject(String bucket, String objectKey);

    void deleteObject(String bucket, String objectKey);

    record PresignedUrl(
        String url,
        OffsetDateTime expiresAt,
        Map<String, String> headers
    ) {
    }
}
