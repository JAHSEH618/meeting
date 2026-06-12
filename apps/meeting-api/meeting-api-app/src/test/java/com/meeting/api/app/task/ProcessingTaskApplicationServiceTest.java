package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskApplicationServiceTest {
    @Test
    void createPublishesCurrentMeetingTranscriptVersionFromJava() {
        CapturingPublisher publisher = new CapturingPublisher();
        ProcessingTaskApplicationService service = new ProcessingTaskApplicationService(
            new InMemoryTaskRepository(),
            new OneMeetingRepository(meetingWithVersions(6, 2)),
            publisher,
            TenantScopedTransaction.immediate(),
            fixedClock()
        );

        service.create(new CreateProcessingTaskCommand(
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            Map.of("enableAsr", true),
            Map.of("chunkStrategyVersion", "v1", "transcriptVersion", 99),
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        ));

        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) publisher.events.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> expectedInputVersion =
            (Map<String, Object>) event.payload().get("expectedInputVersion");
        assertThat(expectedInputVersion)
            .containsEntry("chunkStrategyVersion", "v1")
            .containsEntry("transcriptVersion", 6);
    }

    @Test
    void completedAudioUploadPublishesCurrentMeetingTranscriptVersionFromJava() {
        CapturingPublisher publisher = new CapturingPublisher();
        ProcessingTaskApplicationService service = new ProcessingTaskApplicationService(
            new InMemoryTaskRepository(),
            new OneMeetingRepository(meetingWithVersions(4, 1)),
            publisher,
            TenantScopedTransaction.immediate(),
            fixedClock()
        );

        var dto = service.createForCompletedAudioUpload(
            "tenant_01",
            "meeting_01",
            "audio_01",
            "tos://meeting-audio-auska/audio_01.wav",
            "meeting-audio-auska",
            "audio_01.wav",
            "a".repeat(64),
            1024L,
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        );

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) publisher.events.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> expectedInputVersion =
            (Map<String, Object>) event.payload().get("expectedInputVersion");
        assertThat(expectedInputVersion)
            .containsEntry("chunkStrategyVersion", "v1")
            .containsEntry("transcriptVersion", 4);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-13T02:00:00Z"), ZoneOffset.UTC);
    }

    private static Meeting meetingWithVersions(int transcriptVersion, int minutesVersion) {
        return new Meeting.Builder()
            .id("meeting_01")
            .tenantId("tenant_01")
            .title("Planning")
            .status(MeetingStatus.CREATED)
            .language("zh")
            .transcriptVersion(transcriptVersion)
            .minutesVersion(minutesVersion)
            .createdAt(OffsetDateTime.parse("2026-05-13T02:00:00Z"))
            .createdBy("user_01")
            .participants(List.of())
            .build();
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    private static final class OneMeetingRepository implements MeetingRepository {
        private Meeting meeting;

        private OneMeetingRepository(Meeting meeting) {
            this.meeting = meeting;
        }

        @Override
        public Meeting save(Meeting meeting) {
            this.meeting = meeting;
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id())
                ? Optional.of(meeting)
                : Optional.empty();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return tenantId.equals(meeting.tenantId()) ? List.of(meeting) : List.of();
        }

        @Override
        public void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
            if (tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id())) {
                meeting = new Meeting.Builder()
                    .id(meeting.id())
                    .tenantId(meeting.tenantId())
                    .title(meeting.title())
                    .status(status)
                    .language(meeting.language())
                    .transcriptVersion(meeting.transcriptVersion())
                    .minutesVersion(meeting.minutesVersion())
                    .createdAt(meeting.createdAt())
                    .createdBy(meeting.createdBy())
                    .participants(meeting.participants())
                    .build();
            }
        }
    }

    private static final class InMemoryTaskRepository implements ProcessingTaskRepository {
        private ProcessingTask task;

        @Override
        public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId())
                ? Optional.of(task)
                : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return task != null && tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId())
                ? Optional.of(task)
                : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }
}
