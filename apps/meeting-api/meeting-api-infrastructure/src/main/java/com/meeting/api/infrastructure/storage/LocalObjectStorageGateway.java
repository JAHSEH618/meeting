package com.meeting.api.infrastructure.storage;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.app.common.ApplicationException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalObjectStorageGateway implements ObjectStorageGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorageGateway.class);

    private final String endpoint;
    private final String bucket;
    private final Path localRoot;

    public LocalObjectStorageGateway(
        @Value("${meeting.storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${meeting.storage.bucket:meeting-local}") String bucket,
        @Value("${meeting.storage.local-root:}") String localRoot
    ) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.bucket = bucket;
        this.localRoot = localRoot == null || localRoot.isBlank()
            ? null
            : Paths.get(localRoot);
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

    /**
     * Local-disk implementation of upload: if
     * {@code meeting.storage.local-root} is set, materializes the bytes
     * to {@code <root>/<bucket>/<objectKey>} so the dev loop can verify
     * file contents end-to-end. Otherwise it logs and returns a
     * descriptor — useful for tests where we don't care about the side
     * effect.
     */
    @Override
    public StorageObject putObject(
        String bucket, String objectKey, byte[] bytes,
        String contentType, String sha256
    ) {
        if (localRoot != null) {
            try {
                Path target = localRoot.resolve(bucket).resolve(objectKey);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException ex) {
                throw new ApplicationException(
                    ErrorCode.INTERNAL_ERROR, 500,
                    "failed to persist object to local storage: " + ex.getMessage(),
                    true
                );
            }
        } else {
            log.debug("storage_put_object_inmem bucket={} key={} bytes={}",
                bucket, objectKey, bytes.length);
        }
        return new StorageObject(
            bucket, objectKey, bytes.length, sha256, null, OffsetDateTime.now()
        );
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
