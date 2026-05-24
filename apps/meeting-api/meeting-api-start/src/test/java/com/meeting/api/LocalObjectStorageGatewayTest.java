package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.infrastructure.storage.LocalObjectStorageGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageGatewayTest {

    @Test
    void statReturnsRealSizeWhenLocalMirrorMaterializedTheObject(@TempDir Path tmp) throws Exception {
        LocalObjectStorageGateway gateway = new LocalObjectStorageGateway(
            "http://localhost:9000",
            "meeting-audio-auska",
            tmp.toString()
        );
        Path target = tmp.resolve("meeting-audio-auska").resolve("tenant/t/meeting/m/upload/u/raw");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "hello-local-mirror");

        StorageObject stat = gateway.statObject("meeting-audio-auska", "tenant/t/meeting/m/upload/u/raw");

        assertThat(stat.sizeBytes()).isEqualTo(Files.size(target));
        assertThat(stat.sha256()).isNull();
    }

    @Test
    void statFailsFastWhenLocalMirrorConfiguredButObjectMissing(@TempDir Path tmp) {
        // localRoot is set — caller expects real materialization, so the
        // gateway must surface a 404 instead of returning the -1 sentinel
        // (which would silently bypass the upload-completion size check).
        LocalObjectStorageGateway gateway = new LocalObjectStorageGateway(
            "http://localhost:9000",
            "meeting-audio-auska",
            tmp.toString()
        );

        assertThatThrownBy(() ->
            gateway.statObject("meeting-audio-auska", "tenant/t/meeting/m/upload/u/raw")
        )
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.OSS_OBJECT_NOT_FOUND);
    }

    @Test
    void statReturnsMinusOneSentinelWhenNoLocalMirrorConfigured() {
        // No localRoot — pure in-memory dev mode where the application
        // service is expected to skip the size check. This keeps tests
        // that don't actually materialize bytes working.
        LocalObjectStorageGateway gateway = new LocalObjectStorageGateway(
            "http://localhost:9000",
            "meeting-audio-auska",
            ""
        );

        StorageObject stat = gateway.statObject("meeting-audio-auska", "anything");

        assertThat(stat.sizeBytes()).isEqualTo(-1L);
    }
}
