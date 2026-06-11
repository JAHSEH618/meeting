package com.meeting.api.infrastructure.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosClientException;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.auth.StaticCredentials;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
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
            client.close();
        }
    }

    @Override
    public String defaultBucket() {
        return defaultBucket;
    }

    @Override
    public PresignedUrl presignPut(String bucket, String objectKey, int partNumber, String contentType, OffsetDateTime expiresAt) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public StorageObject putObject(String bucket, String objectKey, byte[] bytes, String contentType, String sha256) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
