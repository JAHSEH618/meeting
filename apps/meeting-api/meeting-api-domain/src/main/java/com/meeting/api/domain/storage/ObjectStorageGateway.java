package com.meeting.api.domain.storage;

import java.time.OffsetDateTime;
import java.util.Map;

public interface ObjectStorageGateway {
    String defaultBucket();

    PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt);

    PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt);

    StorageObject statObject(String bucket, String objectKey);

    void deleteObject(String bucket, String objectKey);

    /**
     * Upload a fully-formed byte payload directly. Used by server-side
     * renderers (export, deletion certificate) that don't need
     * presigned multipart uploads.
     *
     * @param bucket      target bucket
     * @param objectKey   target object key
     * @param bytes       payload
     * @param contentType MIME type, e.g. {@code application/pdf}
     * @param sha256      payload digest (without prefix); the gateway may
     *                    re-compute or trust this value
     * @return descriptor of the persisted object
     */
    StorageObject putObject(
        String bucket, String objectKey, byte[] bytes,
        String contentType, String sha256
    );

    record PresignedUrl(
        String url,
        OffsetDateTime expiresAt,
        Map<String, String> headers
    ) {
    }
}
