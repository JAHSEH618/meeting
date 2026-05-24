package com.meeting.api.infrastructure.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aliyun OSS-backed {@link ObjectStorageGateway}. Activated when
 * {@code meeting.storage.type=oss}; expects endpoint, region, access-key-id,
 * access-key-secret wired through {@code meeting.storage.oss.*} (env:
 * {@code OSS_ENDPOINT}, {@code OSS_REGION}, {@code OSS_ACCESS_KEY_ID},
 * {@code OSS_ACCESS_KEY_SECRET}).
 *
 * <p>Single-PUT mode: this gateway pairs with
 * {@code AudioUploadApplicationService}, which forces {@code partSize >= fileSize}
 * so the multipart loop always degenerates to one PUT against the session's
 * object key. {@link #statObject} returns {@code sha256=null} (OSS ETag is not
 * a SHA-256, so we don't pretend it is — the upload completion path already
 * skips the comparison when sha256 is null). Files &gt; 2 GiB are rejected
 * up-front in createSession. When/if that ceiling becomes a constraint, add
 * {@code oss_upload_id} to {@code audio_upload_sessions}, thread it through
 * the gateway interface, and replace {@link #presignPut} with a proper
 * UploadPart presigner.
 */
@Component
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "oss")
public class AliyunOssObjectStorageGateway implements ObjectStorageGateway {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssObjectStorageGateway.class);

    private final OSS client;
    private final String defaultBucket;

    public AliyunOssObjectStorageGateway(
        @Value("${meeting.storage.oss.endpoint:}") String endpoint,
        @Value("${meeting.storage.oss.region:}") String region,
        @Value("${meeting.storage.oss.access-key-id:}") String accessKeyId,
        @Value("${meeting.storage.oss.access-key-secret:}") String accessKeySecret,
        @Value("${meeting.storage.bucket-audio:meeting-audio-auska}") String defaultBucket
    ) {
        if (endpoint == null || endpoint.isBlank()
            || region == null || region.isBlank()
            || accessKeyId == null || accessKeyId.isBlank()
            || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                "meeting.storage.oss.{endpoint,region,access-key-id,access-key-secret} are all required when meeting.storage.type=oss"
            );
        }
        ClientBuilderConfiguration cfg = new ClientBuilderConfiguration();
        cfg.setSignatureVersion(SignVersion.V4);
        this.client = OSSClientBuilder.create()
            .endpoint(endpoint)
            .credentialsProvider(CredentialsProviderFactory.newDefaultCredentialProvider(accessKeyId, accessKeySecret))
            .clientConfiguration(cfg)
            .region(region)
            .build();
        this.defaultBucket = defaultBucket;
        log.info("oss_gateway_initialized endpoint={} region={} defaultBucket={}", endpoint, region, defaultBucket);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Override
    public String defaultBucket() {
        return defaultBucket;
    }

    @Override
    public PresignedUrl presignPut(
        String bucket, String objectKey, int partNumber,
        String contentType, OffsetDateTime expiresAt
    ) {
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.PUT);
        req.setExpiration(toDate(expiresAt));
        if (contentType != null && !contentType.isBlank()) {
            req.setContentType(contentType);
        }
        URL signed = client.generatePresignedUrl(req);
        Map<String, String> headers = (contentType != null && !contentType.isBlank())
            ? Map.of("Content-Type", contentType)
            : Map.of();
        return new PresignedUrl(signed.toString(), expiresAt, headers);
    }

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        URL signed = client.generatePresignedUrl(bucket, objectKey, toDate(expiresAt));
        return new PresignedUrl(signed.toString(), expiresAt, Map.of());
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        try {
            ObjectMetadata meta = client.getObjectMetadata(bucket, objectKey);
            OffsetDateTime modified = meta.getLastModified() != null
                ? OffsetDateTime.ofInstant(meta.getLastModified().toInstant(), ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);
            // OSS ETag is MD5 of the body for single-PUT objects and a
            // composite digest for multipart; in neither case is it a
            // SHA-256. We deliberately return ``sha256=null`` so the
            // upload-completion path skips the stored-vs-claimed SHA-256
            // comparison.
            //
            // TRUST BOUNDARY (important): this gateway does NOT verify
            // content hash server-side. The completion path only verifies
            // object existence + sizeBytes; the per-part SHA-256 the
            // client submitted is compared against the client's own
            // earlier claim, not recomputed against the stored bytes. An
            // adversary who can write same-size, different-content bytes
            // can pass completion. To strengthen, either:
            //   (a) compute SHA-256 server-side by downloading the object
            //       (expensive — only do this for security-sensitive
            //       buckets out-of-band);
            //   (b) require the client to write the SHA-256 to an OSS
            //       user-metadata header (``x-oss-meta-sha256``) on PUT
            //       and read it back here via ``meta.getUserMetadata()``;
            //   (c) subscribe to OSS event notifications and verify hash
            //       asynchronously.
            return new StorageObject(
                bucket, objectKey,
                meta.getContentLength(),
                /* sha256 */ null,
                meta.getETag(),
                modified
            );
        } catch (Exception ex) {
            throw new ApplicationException(
                ErrorCode.OSS_OBJECT_NOT_FOUND, 404,
                "oss head failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            client.deleteObject(bucket, objectKey);
        } catch (Exception ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "oss delete failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    @Override
    public StorageObject putObject(
        String bucket, String objectKey, byte[] bytes,
        String contentType, String sha256
    ) {
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(bytes.length);
            if (contentType != null && !contentType.isBlank()) {
                meta.setContentType(contentType);
            }
            PutObjectRequest req = new PutObjectRequest(
                bucket, objectKey, new ByteArrayInputStream(bytes), meta
            );
            PutObjectResult result = client.putObject(req);
            return new StorageObject(
                bucket, objectKey, bytes.length, sha256,
                result != null ? result.getETag() : null,
                OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (Exception ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "oss put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    private static Date toDate(OffsetDateTime t) {
        return Date.from(t.toInstant());
    }
}
