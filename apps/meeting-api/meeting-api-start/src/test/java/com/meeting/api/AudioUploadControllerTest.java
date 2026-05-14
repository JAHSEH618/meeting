package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.storage.AudioUploadController;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.enums.AudioUploadStatus;
import com.meeting.api.client.storage.AbortAudioUploadCommand;
import com.meeting.api.client.storage.AudioUploadFacade;
import com.meeting.api.client.storage.AudioUploadPartUploadDTO;
import com.meeting.api.client.storage.AudioUploadSessionDTO;
import com.meeting.api.client.storage.CompleteAudioUploadCommand;
import com.meeting.api.client.storage.CreateAudioUploadPartCommand;
import com.meeting.api.client.storage.CreateAudioUploadSessionCommand;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioUploadControllerTest {

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createSessionBuildsCommandFromTenantContextAndPath() {
        CapturingAudioUploadFacade facade = new CapturingAudioUploadFacade();
        AudioUploadController controller = new AudioUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<AudioUploadSessionDTO> response = controller.create(
            "meeting_01",
            "req_01",
            "trace_01",
            "idem_01",
            new AudioUploadController.CreateAudioUploadRequest(
                "standup.wav",
                "audio/wav",
                1024,
                sha('a'),
                null
            )
        );

        assertThat(response.success()).isTrue();
        assertThat(facade.lastCreate.tenantId()).isEqualTo("tenant_01");
        assertThat(facade.lastCreate.meetingId()).isEqualTo("meeting_01");
        assertThat(facade.lastCreate.requestedBy()).isEqualTo("user_01");
        assertThat(facade.lastCreate.idempotencyKey()).isEqualTo("idem_01");
    }

    @Test
    void createPartBuildsCommandFromTenantContextAndPath() {
        CapturingAudioUploadFacade facade = new CapturingAudioUploadFacade();
        AudioUploadController controller = new AudioUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<AudioUploadPartUploadDTO> response = controller.createPart(
            "meeting_01",
            "upl_01",
            "req_01",
            "trace_01",
            "idem_02",
            new AudioUploadController.CreateAudioUploadPartRequest(1, 1024, sha('b'))
        );

        assertThat(response.success()).isTrue();
        assertThat(facade.lastPart.uploadId()).isEqualTo("upl_01");
        assertThat(facade.lastPart.partNumber()).isEqualTo(1);
        assertThat(facade.lastPart.partSha256()).isEqualTo(sha('b'));
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class CapturingAudioUploadFacade implements AudioUploadFacade {
        private final AudioUploadSessionDTO session = new AudioUploadSessionDTO(
            "upl_01",
            "meeting_01",
            AudioUploadStatus.INITIATED,
            OffsetDateTime.parse("2026-05-14T02:00:00Z"),
            8388608,
            10000,
            "meeting-audio/tenant_01/meeting_01/upl_01/raw",
            "meeting-local",
            "audio/wav",
            "standup.wav",
            1024,
            sha('a'),
            null,
            List.of()
        );
        private CreateAudioUploadSessionCommand lastCreate;
        private CreateAudioUploadPartCommand lastPart;

        @Override
        public AudioUploadSessionDTO createSession(CreateAudioUploadSessionCommand command) {
            lastCreate = command;
            return session;
        }

        @Override
        public AudioUploadPartUploadDTO createPart(CreateAudioUploadPartCommand command) {
            lastPart = command;
            return new AudioUploadPartUploadDTO(
                command.uploadId(),
                command.partNumber(),
                command.partSha256(),
                null,
                "http://localhost:9000/meeting-local/object",
                OffsetDateTime.parse("2026-05-14T02:15:00Z"),
                Map.of()
            );
        }

        @Override
        public AudioUploadSessionDTO complete(CompleteAudioUploadCommand command) {
            return session;
        }

        @Override
        public AudioUploadSessionDTO abort(AbortAudioUploadCommand command) {
            return session;
        }

        @Override
        public Optional<AudioUploadSessionDTO> get(String tenantId, String meetingId, String uploadId) {
            return Optional.of(session);
        }
    }
}
