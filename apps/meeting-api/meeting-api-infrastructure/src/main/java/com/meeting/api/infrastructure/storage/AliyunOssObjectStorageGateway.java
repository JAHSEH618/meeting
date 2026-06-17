package com.meeting.api.infrastructure.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
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

@Component
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "oss")
public class AliyunOssObjectStorageGateway implements ObjectStorageGateway {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssObjectStorageGateway.class);

    private final OSS client;
    private final String defaultBucket;

    public AliyunOssObjectStorageGateway(
        @Value("${meeting.storage.oss.endpoint:}") String endpoint,
        @Value("${meeting.storage.oss.region:cn-hangzhou}") String region,
        @Value("${meeting.storage.oss.access-key-id:}") String accessKeyId,
        @Value("${meeting.storage.oss.access-key-secret:}") String accessKeySecret,
        @Value("${meeting.storage.bucket-audio:meeting-audio-auska}") String defaultBucket
    ) {
        if (endpoint == null || endpoint.isBlank()
            || accessKeyId == null || accessKeyId.isBlank()
            || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                "meeting.storage.oss.{endpoint,access-key-id,access-key-secret} " +
                "are all required when meeting.storage.type=oss"
            );
        }
        this.client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.defaultBucket = defaultBucket;
        log.info("aliyun_oss_gateway_initialized endpoint={} region={} defaultBucket={}",
            endpoint, region, defaultBucket);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ex) {
                log.warn("aliyun_oss_client_shutdown_failed", ex);
            }
        }
    }

    @Override
    public String defaultBucket() {
        return defaultBucket;
    }

    @Override
    public PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt) {
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.PUT);
            request.setExpiration(expiration(expiresAt));
            Map<String, String> headers = Map.of();
            if (contentType != null && !contentType.isBlank()) {
                request.setContentType(contentType);
                headers = Map.of("Content-Type", contentType);
            }
            URL url = client.generatePresignedUrl(request);
            return new PresignedUrl(url.toString(), expiresAt, headers);
        } catch (ClientException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 500,
                "aliyun oss presign put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(expiration(expiresAt));
            URL url = client.generatePresignedUrl(request);
            return new PresignedUrl(url.toString(), expiresAt, Map.of());
        } catch (ClientException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 500,
                "aliyun oss presign get failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        try {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, objectKey);
            OffsetDateTime modified = metadata.getLastModified() != null
                ? OffsetDateTime.ofInstant(metadata.getLastModified().toInstant(), ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);
            return new StorageObject(
                bucket,
                objectKey,
                metadata.getContentLength(),
                null,
                metadata.getETag(),
                modified
            );
        } catch (OSSException | ClientException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_OBJECT_NOT_FOUND, 404,
                "aliyun oss head failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            client.deleteObject(bucket, objectKey);
        } catch (OSSException | ClientException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "aliyun oss delete failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    @Override
    public StorageObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, String sha256) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            if (contentType != null && !contentType.isBlank()) {
                metadata.setContentType(contentType);
            }
            PutObjectResult output = client.putObject(new PutObjectRequest(
                bucket,
                objectKey,
                new ByteArrayInputStream(bytes),
                metadata
            ));
            return new StorageObject(
                bucket,
                objectKey,
                bytes.length,
                sha256,
                output != null ? output.getETag() : null,
                OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (OSSException | ClientException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "aliyun oss put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    private static Date expiration(OffsetDateTime expiresAt) {
        return Date.from(expiresAt.toInstant());
    }
}
