package com.meeting.api;

import com.meeting.api.start.config.StorageConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageConfigValidatorTest {

    @Test
    void localStorageRequiresLocalRoot() {
        StorageConfigValidator validator = validator("local", "");

        assertThatThrownBy(() -> validator.onApplicationEvent(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("meeting.storage.local-root");
    }

    @Test
    void minioStorageDoesNotRequireLocalRoot() {
        StorageConfigValidator validator = validator("minio", "");

        assertThatCode(() -> validator.onApplicationEvent(null))
            .doesNotThrowAnyException();
    }

    private static StorageConfigValidator validator(String storageType, String localRoot) {
        StorageConfigValidator validator = new StorageConfigValidator();
        ReflectionTestUtils.setField(validator, "storageType", storageType);
        ReflectionTestUtils.setField(validator, "localRoot", localRoot);
        return validator;
    }
}
