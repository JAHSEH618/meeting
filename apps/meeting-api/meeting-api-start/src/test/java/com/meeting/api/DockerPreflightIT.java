package com.meeting.api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker availability check - disabled in CI since Docker is not always available.
 * Other IT tests use TestcontainersDockerPreflight.assumeDockerAvailable() to skip gracefully.
 * This test is only useful for local verification.
 */
@Disabled("Docker not available in all CI environments - use './mvnw test' for unit tests, './mvnw verify' when Docker is available")
class DockerPreflightIT {

    @Test
    void testcontainersDockerEnvironmentShouldBeAvailable() {
        assertThat(TestcontainersDockerPreflight.isDockerAvailable())
            .as(TestcontainersDockerPreflight.message())
            .isTrue();
    }
}
