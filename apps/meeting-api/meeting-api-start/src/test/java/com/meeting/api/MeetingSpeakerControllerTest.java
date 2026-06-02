package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.adapter.speaker.MeetingSpeakerController;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.MeetingSpeakerApplicationService;
import com.meeting.api.client.speaker.MeetingSpeakerCandidateDTO;
import com.meeting.api.client.speaker.MeetingSpeakerDTO;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingSpeakerControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void listReturnsOpenApiEnvelopeWithoutLeakingInternalSpeakerFields() {
        RecordingMeetingSpeakerService service = new RecordingMeetingSpeakerService(List.of(new MeetingSpeakerDTO(
            "SPEAKER_00",
            "Alice Profile",
            "person_01",
            "profile_01",
            "CANDIDATE",
            0.91,
            OffsetDateTime.parse("2026-06-02T03:00:00Z"),
            List.of(new MeetingSpeakerCandidateDTO("person_01", "profile_01", "Alice Profile", 0.91))
        )));
        MeetingSpeakerController controller = new MeetingSpeakerController(service);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.list("meeting_01", "req_01", "trace_01");

        assertThat(service.lastTenantId).isEqualTo("tenant_01");
        assertThat(service.lastMeetingId).isEqualTo("meeting_01");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode json = JSON.valueToTree(response.getBody());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.at("/data/meetingId").asText()).isEqualTo("meeting_01");
        assertThat(json.at("/data/speakers").isArray()).isTrue();
        assertThat(json.at("/data/speakers/0/speakerLabel").asText()).isEqualTo("SPEAKER_00");
        assertThat(json.at("/data/speakers/0/displayName").asText()).isEqualTo("Alice Profile");
        assertThat(json.at("/data/speakers/0/personId").asText()).isEqualTo("person_01");
        assertThat(json.at("/data/speakers/0/speakerProfileId").asText()).isEqualTo("profile_01");
        assertThat(json.at("/data/speakers/0/confirmationStatus").asText()).isEqualTo("CANDIDATE");
        assertThat(json.at("/data/speakers/0/candidates/0/personId").asText()).isEqualTo("person_01");
        assertThat(json.at("/data/speakers/0/candidates/0/speakerProfileId").asText()).isEqualTo("profile_01");
        assertThat(json.at("/data/speakers/0/candidates/0/displayName").asText()).isEqualTo("Alice Profile");
        assertThat(json.at("/data/speakers/0/candidates/0/confidence").asDouble()).isEqualTo(0.91);
        assertThat(json.at("/data/0").isMissingNode()).isTrue();
        assertThat(json.at("/data/speakers/0/autoMatchScore").isMissingNode()).isTrue();
        assertThat(json.at("/data/speakers/0/confirmedAt").isMissingNode()).isTrue();
        assertThat(json.path("error").isNull()).isTrue();
        assertThat(json.path("requestId").asText()).isEqualTo("req_01");
        assertThat(json.path("traceId").asText()).isEqualTo("trace_01");
    }

    @Test
    void listFailsClosedWhenTenantContextIsMissing() {
        MeetingSpeakerController controller = new MeetingSpeakerController(new RecordingMeetingSpeakerService(List.of()));

        assertThatThrownBy(() -> controller.list("meeting_01", "req_01", "trace_01"))
            .isInstanceOf(TenantContextMissingException.class);
    }

    private static final class RecordingMeetingSpeakerService extends MeetingSpeakerApplicationService {
        private final List<MeetingSpeakerDTO> speakers;
        private String lastTenantId;
        private String lastMeetingId;

        private RecordingMeetingSpeakerService(List<MeetingSpeakerDTO> speakers) {
            super(null, null, null, null, TenantScopedTransaction.immediate(), Clock.systemUTC());
            this.speakers = speakers;
        }

        @Override
        public List<MeetingSpeakerDTO> list(String tenantId, String meetingId) {
            this.lastTenantId = tenantId;
            this.lastMeetingId = meetingId;
            return speakers;
        }
    }
}
