package com.meeting.api;

import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.infrastructure.storage.LocalObjectStorageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class StorageGatewayConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(StorageGatewaySlice.class);

    @Test
    void minioStorageTypeUsesLocalGatewayShim() {
        contextRunner
            .withPropertyValues("meeting.storage.type=minio")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ObjectStorageGateway.class);
                assertThat(context.getBean(ObjectStorageGateway.class))
                    .isInstanceOf(LocalObjectStorageGateway.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LocalObjectStorageGateway.class)
    static class StorageGatewaySlice {
    }
}
