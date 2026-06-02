package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.speaker.SpeakerProfileController;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.client.speaker.SpeakerEnrollmentDTO;
import com.meeting.api.client.speaker.SpeakerProfileDTO;
import com.meeting.api.client.speaker.SpeakerProfileFacade;
import com.meeting.api.client.speaker.SpeakerProfileListDTO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerProfileControllerTest {
    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createMapsOpenApiConsentReferenceToApplicationCommand() {
        CapturingSpeakerProfileFacade facade = new CapturingSpeakerProfileFacade();
        SpeakerProfileController controller = new SpeakerProfileController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.create(
            new SpeakerProfileController.CreateProfileRequest(
                "person_01",
                "李四",
                "USER_ENROLLMENT:v1",
                null,
                null
            ),
            "req_01",
            "trace_01",
            "idem_01",
            "user_01"
        );

        assertThat(facade.lastCreate.tenantId()).isEqualTo("tenant_01");
        assertThat(facade.lastCreate.personId()).isEqualTo("person_01");
        assertThat(facade.lastCreate.displayName()).isEqualTo("李四");
        assertThat(facade.lastCreate.consentSource()).isEqualTo("USER_ENROLLMENT");
        assertThat(facade.lastCreate.consentVersion()).isEqualTo("v1");
        assertThat(facade.lastCreate.idempotencyKey()).isEqualTo("idem_01");
    }

    @Test
    void addEnrollmentMapsOpenApiAudioFileIdToApplicationCommand() {
        CapturingSpeakerProfileFacade facade = new CapturingSpeakerProfileFacade();
        SpeakerProfileController controller = new SpeakerProfileController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        controller.addEnrollment(
            "spk_01",
            new SpeakerProfileController.CreateEnrollmentRequest(
                "file_01",
                null,
                "USER_ENROLLMENT:v1",
                "zh"
            ),
            "req_01",
            "trace_01",
            "idem_02",
            "user_01"
        );

        assertThat(facade.lastEnrollment.tenantId()).isEqualTo("tenant_01");
        assertThat(facade.lastEnrollment.speakerProfileId()).isEqualTo("spk_01");
        assertThat(facade.lastEnrollment.sourceAudioFileId()).isEqualTo("file_01");
        assertThat(facade.lastEnrollment.idempotencyKey()).isEqualTo("idem_02");
    }

    @Test
    void addEnrollmentRejectsMissingAudioFileId() {
        CapturingSpeakerProfileFacade facade = new CapturingSpeakerProfileFacade();
        SpeakerProfileController controller = new SpeakerProfileController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.addEnrollment(
            "spk_01",
            new SpeakerProfileController.CreateEnrollmentRequest(
                null,
                "legacy_file_01",
                "USER_ENROLLMENT:v1",
                "zh"
            ),
            "req_01",
            "trace_01",
            "idem_02",
            "user_01"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("audioFileId is required");

        assertThat(facade.lastEnrollment).isNull();
    }

    @Test
    void addEnrollmentRejectsMissingConsentReference() {
        CapturingSpeakerProfileFacade facade = new CapturingSpeakerProfileFacade();
        SpeakerProfileController controller = new SpeakerProfileController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        assertThatThrownBy(() -> controller.addEnrollment(
            "spk_01",
            new SpeakerProfileController.CreateEnrollmentRequest(
                "file_01",
                null,
                null,
                "zh"
            ),
            "req_01",
            "trace_01",
            "idem_02",
            "user_01"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("consentReference is required");

        assertThat(facade.lastEnrollment).isNull();
    }

    @Test
    void listReturnsOpenApiEnvelopeWithItemsAndPage() {
        CapturingSpeakerProfileFacade facade = new CapturingSpeakerProfileFacade();
        facade.listResult = new SpeakerProfileListDTO(
            List.of(new SpeakerProfileListDTO.Item(
                "spk_01",
                "person_01",
                "李四",
                "ACTIVE",
                null,
                null
            )),
            new SpeakerProfileListDTO.PageInfo(null, false, 1)
        );
        SpeakerProfileController controller = new SpeakerProfileController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.list("person_01", "req_01", "trace_01");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().items()).singleElement().satisfies(profile -> {
            assertThat(profile.speakerProfileId()).isEqualTo("spk_01");
            assertThat(profile.status()).isEqualTo("ACTIVE");
        });
        assertThat(response.getBody().data().page().hasMore()).isFalse();
        assertThat(response.getBody().data().page().limit()).isEqualTo(1);
        assertThat(facade.lastListTenantId).isEqualTo("tenant_01");
        assertThat(facade.lastListPersonId).isEqualTo("person_01");
    }

    private static final class CapturingSpeakerProfileFacade implements SpeakerProfileFacade {
        private CreateSpeakerProfileCommand lastCreate;
        private CreateSpeakerEnrollmentCommand lastEnrollment;
        private SpeakerProfileListDTO listResult = new SpeakerProfileListDTO(
            List.of(),
            new SpeakerProfileListDTO.PageInfo(null, false, 0)
        );
        private String lastListTenantId;
        private String lastListPersonId;

        @Override
        public SpeakerProfileDTO create(CreateSpeakerProfileCommand command) {
            lastCreate = command;
            return new SpeakerProfileDTO(
                "spk_01",
                command.tenantId(),
                command.personId(),
                command.displayName(),
                "ACTIVE",
                command.consentSource(),
                command.consentVersion(),
                null,
                null,
                NOW,
                NOW
            );
        }

        @Override
        public Optional<SpeakerProfileDTO> get(String tenantId, String profileId) {
            return Optional.empty();
        }

        @Override
        public SpeakerProfileListDTO list(String tenantId, String personId) {
            lastListTenantId = tenantId;
            lastListPersonId = personId;
            return listResult;
        }

        @Override
        public void revoke(String tenantId, String profileId, String revokedBy, String reason) {
        }

        @Override
        public void delete(String tenantId, String profileId, String deletedBy, String reason) {
        }

        @Override
        public SpeakerEnrollmentDTO addEnrollment(CreateSpeakerEnrollmentCommand command) {
            lastEnrollment = command;
            return new SpeakerEnrollmentDTO(
                "spe_01",
                command.speakerProfileId(),
                command.tenantId(),
                command.sourceAudioFileId(),
                "PENDING",
                null,
                null,
                null,
                NOW,
                NOW
            );
        }

        @Override
        public List<SpeakerEnrollmentDTO> listEnrollments(String tenantId, String profileId) {
            return List.of();
        }
    }

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-02T10:00:00Z");
}
