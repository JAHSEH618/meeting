package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.storage.FileUploadController;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.storage.AbortGenericFileUploadCommand;
import com.meeting.api.client.storage.CompleteGenericFileUploadCommand;
import com.meeting.api.client.storage.CreateGenericFilePartCommand;
import com.meeting.api.client.storage.CreateGenericFileUploadCommand;
import com.meeting.api.client.storage.GenericFileCompleteDTO;
import com.meeting.api.client.storage.GenericFileFacade;
import com.meeting.api.client.storage.GenericFileUploadPartDTO;
import com.meeting.api.client.storage.GenericFileUploadSessionDTO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileUploadControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createSessionBuildsCommandFromTenantContext() {
        CapturingGenericFileFacade facade = new CapturingGenericFileFacade();
        FileUploadController controller = new FileUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<GenericFileUploadSessionDTO> response = controller.create(
            "req_01",
            "trace_01",
            "idem_01",
            new FileUploadController.CreateFileUploadRequest(
                "ref.pdf",
                "application/pdf",
                1024,
                sha('a'),
                null
            )
        );

        assertThat(response.success()).isTrue();
        assertThat(facade.lastCreate.tenantId()).isEqualTo("tenant_01");
        assertThat(facade.lastCreate.requestedBy()).isEqualTo("user_01");
        assertThat(facade.lastCreate.idempotencyKey()).isEqualTo("idem_01");
        assertThat(facade.lastCreate.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void createSessionSerializesPartsWithGenericFileUploadPartContractShape() throws Exception {
        CapturingGenericFileFacade facade = new CapturingGenericFileFacade();
        FileUploadController controller = new FileUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<GenericFileUploadSessionDTO> response = controller.create(
            "req_01",
            "trace_01",
            "idem_01",
            new FileUploadController.CreateFileUploadRequest(
                "ref.pdf",
                "application/pdf",
                1024,
                sha('a'),
                null
            )
        );

        JsonNode part = JSON.valueToTree(response).at("/data/parts/0");
        assertThat(part.isMissingNode()).isFalse();
        assertThat(part.get("partNumber").asInt()).isEqualTo(1);
        assertThat(part.hasNonNull("partSha256")).isTrue();
        assertThat(part.hasNonNull("sizeBytes")).isTrue();
        assertThat(part.hasNonNull("uploadUrl")).isTrue();
        assertThat(part.hasNonNull("expiresAt")).isTrue();
        assertThat(part.hasNonNull("headers")).isTrue();
        assertThat(part.has("uploadStatus")).isFalse();
        assertThat(part.has("uploadedAt")).isFalse();
        assertThat(part.has("uploadId")).isFalse();
        assertThat(JSON.valueToTree(response).at("/data").has("uploadStatus")).isFalse();
    }

    @Test
    void createPartSerializesGenericFileUploadPartContractShape() throws Exception {
        CapturingGenericFileFacade facade = new CapturingGenericFileFacade();
        FileUploadController controller = new FileUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<GenericFileUploadPartDTO> response = controller.createPart(
            "upl_01",
            "req_01",
            "trace_01",
            "idem_part",
            new FileUploadController.CreateFileUploadPartRequest(1, 1024, sha('b'))
        );

        JsonNode part = JSON.valueToTree(response).at("/data");
        assertThat(part.get("partNumber").asInt()).isEqualTo(1);
        assertThat(part.hasNonNull("partSha256")).isTrue();
        assertThat(part.hasNonNull("sizeBytes")).isTrue();
        assertThat(part.hasNonNull("uploadUrl")).isTrue();
        assertThat(part.hasNonNull("expiresAt")).isTrue();
        assertThat(part.hasNonNull("headers")).isTrue();
        assertThat(part.has("uploadId")).isFalse();
    }

    @Test
    void completeReturnsContractShape() {
        CapturingGenericFileFacade facade = new CapturingGenericFileFacade();
        FileUploadController controller = new FileUploadController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        ApiResponse<GenericFileCompleteDTO> response = controller.complete(
            "upl_01",
            "req_01",
            "trace_01",
            "idem_complete",
            new FileUploadController.CompleteFileUploadRequest(
                sha('a'),
                List.of(new FileUploadController.CompleteFileUploadPartRequest(1, sha('b'), "etag_01"))
            )
        );

        assertThat(response.data().fileId()).isEqualTo("file_01");
        assertThat(facade.lastComplete.uploadId()).isEqualTo("upl_01");
        assertThat(facade.lastComplete.parts()).hasSize(1);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class CapturingGenericFileFacade implements GenericFileFacade {
        private final GenericFileUploadSessionDTO session = new GenericFileUploadSessionDTO(
            "upl_01",
            OffsetDateTime.parse("2026-05-27T02:00:00Z"),
            8388608,
            10000,
            "tenants/tenant_01/generic-files/upl_01/raw",
            "meeting-local",
            "application/pdf",
            "ref.pdf",
            1024,
            sha('a'),
            null,
            List.of(new GenericFileUploadPartDTO(
                1,
                sha('b'),
                1024,
                null,
                "http://localhost:9000/meeting-local/object",
                OffsetDateTime.parse("2026-05-27T02:15:00Z"),
                Map.of()
            ))
        );
        private CreateGenericFileUploadCommand lastCreate;
        private CompleteGenericFileUploadCommand lastComplete;

        @Override
        public GenericFileUploadSessionDTO createSession(CreateGenericFileUploadCommand command) {
            lastCreate = command;
            return session;
        }

        @Override
        public GenericFileUploadPartDTO createPart(CreateGenericFilePartCommand command) {
            return new GenericFileUploadPartDTO(
                command.partNumber(),
                command.partSha256(),
                command.sizeBytes(),
                null,
                "http://localhost:9000/meeting-local/object",
                OffsetDateTime.parse("2026-05-27T02:15:00Z"),
                Map.of()
            );
        }

        @Override
        public GenericFileCompleteDTO complete(CompleteGenericFileUploadCommand command) {
            lastComplete = command;
            return new GenericFileCompleteDTO("file_01", command.fileSha256(), 1024, "application/pdf");
        }

        @Override
        public void abort(AbortGenericFileUploadCommand command) {
        }

        @Override
        public Optional<GenericFileUploadSessionDTO> get(String tenantId, String uploadId) {
            return Optional.of(session);
        }
    }
}
