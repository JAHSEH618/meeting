package com.meeting.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

final class TestcontainersDockerPreflight {

    private static final String MESSAGE = "Docker daemon is not available to Testcontainers. "
        + "'./mvnw verify' runs PostgreSQL and RabbitMQ baseline integration tests. "
        + "Start Docker/Colima, export DOCKER_HOST if needed, then rerun; "
        + "use './mvnw test' for unit and ArchUnit tests only.";
    private static final Duration DOCKER_INFO_TIMEOUT = Duration.ofSeconds(5);

    private static Boolean dockerAvailable;

    private TestcontainersDockerPreflight() {
    }

    static String message() {
        return MESSAGE;
    }

    static synchronized boolean isDockerAvailable() {
        if (dockerAvailable == null) {
            dockerAvailable = isDockerCliAvailable()
                && hasTestcontainersConnectionHint()
                && DockerClientFactory.instance().isDockerAvailable();
        }
        return dockerAvailable;
    }

    static void assumeDockerAvailable() {
        Assumptions.assumeTrue(isDockerAvailable(), MESSAGE);
    }

    private static boolean isDockerCliAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(DOCKER_INFO_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static boolean hasTestcontainersConnectionHint() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            return true;
        }
        return Files.exists(Path.of("/var/run/docker.sock"))
            || Files.exists(Path.of(System.getProperty("user.home"), ".docker/run/docker.sock"));
    }
}
