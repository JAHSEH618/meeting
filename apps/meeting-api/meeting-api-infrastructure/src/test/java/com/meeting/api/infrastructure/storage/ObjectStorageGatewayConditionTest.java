package com.meeting.api.infrastructure.storage;

import com.meeting.api.domain.storage.ObjectStorageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageGatewayConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(StorageGatewaySlice.class);

    @Test
    void ossStorageTypeUsesAliyunGateway() {
        contextRunner
            .withPropertyValues(
                "meeting.storage.type=oss",
                "meeting.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                "meeting.storage.oss.region=cn-hangzhou",
                "meeting.storage.oss.access-key-id=test-ak",
                "meeting.storage.oss.access-key-secret=test-sk",
                "meeting.storage.bucket-audio=meeting-audio-auska"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ObjectStorageGateway.class);
                assertThat(context.getBean(ObjectStorageGateway.class))
                    .isInstanceOf(AliyunOssObjectStorageGateway.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AliyunOssObjectStorageGateway.class)
    static class StorageGatewaySlice {
    }
}
