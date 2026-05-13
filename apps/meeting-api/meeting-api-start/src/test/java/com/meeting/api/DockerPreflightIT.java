package com.meeting.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerPreflightIT {

    @Test
    void testcontainersDockerEnvironmentShouldBeAvailable() {
        assertThat(TestcontainersDockerPreflight.isDockerAvailable())
            .as(TestcontainersDockerPreflight.message())
            .isTrue();
    }
}
