package com.meeting.api.infrastructure.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosClientException;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.auth.StaticCredentials;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import com.volcengine.tos.model.object.HeadObjectV2Output;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.PutObjectInput;
import com.volcengine.tos.model.object.PutObjectOutput;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.comm.HttpMethod;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "tos")
public class VolcengineTosObjectStorageGateway implements ObjectStorageGateway {

    private static final Logger log = LoggerFactory.getLogger(VolcengineTosObjectStorageGateway.class);

    private final TOSV2 client;
    private final String defaultBucket;

    public VolcengineTosObjectStorageGateway(
        @Value("${meeting.storage.tos.endpoint:}") String endpoint,
        @Value("${meeting.storage.tos.region:cn-beijing}") String region,
        @Value("${meeting.storage.tos.access-key-id:}") String accessKeyId,
        @Value("${meeting.storage.tos.access-key-secret:}") String accessKeySecret,
        @Value("${meeting.storage.bucket-audio:meeting-audio-auska}") String defaultBucket
    ) {
        if (endpoint == null || endpoint.isBlank()
            || region == null || region.isBlank()
            || accessKeyId == null || accessKeyId.isBlank()
            || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                "meeting.storage.tos.{endpoint,region,access-key-id,access-key-secret} " +
                "are all required when meeting.storage.type=tos"
            );
        }
        this.client = new TOSV2ClientBuilder()
            .build(region, endpoint, new StaticCredentials(accessKeyId, accessKeySecret));
        this.defaultBucket = defaultBucket;
        log.info("tos_gateway_initialized endpoint={} region={} defaultBucket={}",
            endpoint, region, defaultBucket);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("tos_client_close_failed", ex);
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
            long expires = expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond();
            PreSignedURLInput.PreSignedURLInputBuilder builder = PreSignedURLInput.builder()
                .httpMethod(HttpMethod.PUT)
                .bucket(bucket)
                .key(objectKey)
                .expires(expires);
            PreSignedURLOutput output = client.preSignedURL(builder.build());
            Map<String, String> headers = (contentType != null && !contentType.isBlank())
                ? Map.of("Content-Type", contentType)
                : Map.of();
            return new PresignedUrl(output.getSignedUrl(), expiresAt, headers);
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 500,
                "tos presign put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        try {
            long expires = expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond();
            PreSignedURLInput input = PreSignedURLInput.builder()
                .httpMethod(HttpMethod.GET)
                .bucket(bucket)
                .key(objectKey)
                .expires(expires)
                .build();
            PreSignedURLOutput output = client.preSignedURL(input);
            return new PresignedUrl(output.getSignedUrl(), expiresAt, Map.of());
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 500,
                "tos presign get failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        try {
            HeadObjectV2Input input = HeadObjectV2Input.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
            HeadObjectV2Output output = client.headObject(input);
            OffsetDateTime modified = output.getLastModifiedInDate() != null
                ? OffsetDateTime.ofInstant(output.getLastModifiedInDate().toInstant(), ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);
            return new StorageObject(
                bucket, objectKey,
                output.getContentLength(),
                null,
                output.getEtag(),
                modified
            );
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_OBJECT_NOT_FOUND, 404,
                "tos head failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                false
            );
        }
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            DeleteObjectInput input = DeleteObjectInput.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
            client.deleteObject(input);
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "tos delete failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    @Override
    public StorageObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, String sha256) {
        try {
            PutObjectInput.PutObjectInputBuilder builder = PutObjectInput.builder()
                .bucket(bucket)
                .key(objectKey)
                .content(new ByteArrayInputStream(bytes))
                .contentLength(bytes.length);
            if (contentType != null && !contentType.isBlank()) {
                ObjectMetaRequestOptions options = new ObjectMetaRequestOptions();
                options.setContentType(contentType);
                builder.options(options);
            }
            PutObjectOutput output = client.putObject(builder.build());
            return new StorageObject(
                bucket, objectKey, bytes.length, sha256,
                output != null ? output.getEtag() : null,
                OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.OSS_WRITE_FAILED, 502,
                "tos put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }
}
