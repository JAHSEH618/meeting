package com.meeting.api;

import com.meeting.api.start.config.StorageConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;

class StorageConfigValidatorTest {

    @Test
    void tosStorageDoesNotThrow() {
        StorageConfigValidator validator = validator("tos");

        assertThatCode(() -> validator.onApplicationEvent(null))
            .doesNotThrowAnyException();
    }

    @Test
    void ossStorageDoesNotThrow() {
        StorageConfigValidator validator = validator("oss");

        assertThatCode(() -> validator.onApplicationEvent(null))
            .doesNotThrowAnyException();
    }

    private static StorageConfigValidator validator(String storageType) {
        StorageConfigValidator validator = new StorageConfigValidator();
        ReflectionTestUtils.setField(validator, "storageType", storageType);
        return validator;
    }
}
