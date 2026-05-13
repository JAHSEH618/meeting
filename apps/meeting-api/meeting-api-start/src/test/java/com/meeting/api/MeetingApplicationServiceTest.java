package com.meeting.api;

import com.meeting.api.app.meeting.MeetingApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingApplicationServiceTest {

    @Test
    void createPersistsMeetingWithDefaultsAndReturnsDto() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        MeetingApplicationService service = new MeetingApplicationService(repository);

        MeetingDTO dto = service.create(new CreateMeetingCommand(
            "tenant_01",
            "Sprint Review",
            OffsetDateTime.parse("2026-01-01T10:00:00Z"),
            null,
            null,
            List.of(new CreateMeetingCommand.ParticipantCommand("person_01", "Ada", "HOST")),
            "user_01"
        ));

        assertThat(repository.saved).hasSize(1);
        Meeting saved = repository.saved.get(0);
        assertThat(saved.id()).startsWith("m_");
        assertThat(saved.tenantId()).isEqualTo("tenant_01");
        assertThat(saved.title()).isEqualTo("Sprint Review");
        assertThat(saved.status()).isEqualTo(MeetingStatus.CREATED);
        assertThat(saved.securityLevel()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(saved.language()).isEqualTo("zh");
        assertThat(saved.participants()).hasSize(1);

        assertThat(dto.meetingId()).isEqualTo(saved.id());
        assertThat(dto.tenantId()).isEqualTo(saved.tenantId());
        assertThat(dto.transcriptVersion()).isZero();
        assertThat(dto.minutesVersion()).isZero();
    }

    @Test
    void getAndListAreTenantScopedThroughRepositoryPort() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        MeetingApplicationService service = new MeetingApplicationService(repository);
        Meeting meeting = Meeting.create(
            "m_01",
            "tenant_01",
            "Planning",
            SecurityLevel.CONFIDENTIAL,
            "en",
            List.of(),
            "user_01"
        );
        repository.save(meeting);

        assertThat(service.get("tenant_01", "m_01")).isPresent();
        assertThat(service.get("tenant_02", "m_01")).isEmpty();
        assertThat(service.list("tenant_01")).extracting(MeetingDTO::meetingId).containsExactly("m_01");
        assertThat(service.list("tenant_02")).isEmpty();
    }

    private static final class CapturingMeetingRepository implements MeetingRepository {
        private final List<Meeting> saved = new ArrayList<>();

        @Override
        public Meeting save(Meeting meeting) {
            saved.removeIf(existing -> existing.id().equals(meeting.id()));
            saved.add(meeting);
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return saved.stream()
                .filter(meeting -> meeting.id().equals(meetingId))
                .filter(meeting -> meeting.tenantId().equals(tenantId))
                .findFirst();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return saved.stream()
                .filter(meeting -> meeting.tenantId().equals(tenantId))
                .toList();
        }
    }
}
