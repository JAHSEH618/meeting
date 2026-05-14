package com.meeting.api.infrastructure.storage;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.app.common.ApplicationException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalObjectStorageGateway implements ObjectStorageGateway {
    private final String endpoint;
    private final String bucket;

    public LocalObjectStorageGateway(
        @Value("${meeting.storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${meeting.storage.bucket:meeting-local}") String bucket
    ) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.bucket = bucket;
    }

    @Override
    public String defaultBucket() {
        return bucket;
    }

    @Override
    public PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt) {
        return new PresignedUrl(
            objectUrl(bucket, objectKey) + "?partNumber=" + partNumber + "&expiresAt=" + encode(expiresAt.toString()),
            expiresAt,
            Map.of("Content-Type", contentType)
        );
    }

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        return new PresignedUrl(
            objectUrl(bucket, objectKey) + "?expiresAt=" + encode(expiresAt.toString()),
            expiresAt,
            Map.of()
        );
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        return new StorageObject(bucket, objectKey, 0, null, null, OffsetDateTime.now());
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        // P2-3 replaces this local stub with MinIO/TOS SDK deletion.
    }

    private String objectUrl(String bucket, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApplicationException(ErrorCode.TOS_OBJECT_NOT_FOUND, 404, "object key is blank", false);
        }
        return endpoint + "/" + encode(bucket) + "/" + objectKey;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
