package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.infrastructure.mq.ProcessingTaskMessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.meeting.api.client.enums.SecurityLevel.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskApplicationServiceTest {

    @Test
    void createTaskMarksAudioUploadSucceededAndPublishesOnlyWorkerSteps() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        CapturingPublisher publisher = new CapturingPublisher();
        ProcessingTaskApplicationService service = new ProcessingTaskApplicationService(
            tasks,
            new OneMeetingRepository(),
            publisher,
            TenantScopedTransaction.immediate(),
            fixedClock()
        );

        var dto = service.create(new CreateProcessingTaskCommand(
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            Map.of("enableAsr", true),
            Map.of("chunkStrategyVersion", "v1"),
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        ));

        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(dto.currentStep()).isEqualTo("AUDIO_PREPROCESS");
        assertThat(dto.steps())
            .filteredOn(step -> step.stepName() == ProcessingStep.AUDIO_UPLOAD)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.status()).isEqualTo(StepStatus.SUCCEEDED);
                assertThat(step.source()).isEqualTo(ProcessingStepUpdateSource.JAVA_TASK_SERVICE);
            });
        assertThat(dto.steps())
            .filteredOn(step -> step.stepName() == ProcessingStep.SUMMARY || step.stepName() == ProcessingStep.EXTRACTION)
            .hasSize(2)
            .allSatisfy(step -> {
                assertThat(step.status()).isEqualTo(StepStatus.PENDING);
                assertThat(step.source()).isEqualTo(ProcessingStepUpdateSource.JAVA_TASK_SERVICE);
            });
        assertThat(dto.steps())
            .extracting("stepName")
            .containsExactly(
                ProcessingStep.AUDIO_UPLOAD,
                ProcessingStep.AUDIO_PREPROCESS,
                ProcessingStep.ASR,
                ProcessingStep.ALIGNMENT,
                ProcessingStep.DIARIZATION,
                ProcessingStep.SPEAKER_EMBEDDING,
                ProcessingStep.SPEAKER_MATCHING,
                ProcessingStep.TRANSCRIPT_MERGE,
                ProcessingStep.RAG_INDEXING,
                ProcessingStep.SUMMARY,
                ProcessingStep.EXTRACTION
            );

        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) publisher.events.get(0);
        assertThat(event.pipelineSteps()).doesNotContain(
            ProcessingStep.AUDIO_UPLOAD,
            ProcessingStep.SUMMARY,
            ProcessingStep.EXTRACTION,
            ProcessingStep.EXPORT
        );
        @SuppressWarnings("unchecked")
        List<String> pipelineSteps = (List<String>) event.payload().get("pipelineSteps");
        assertThat(pipelineSteps)
            .containsExactly(
                "AUDIO_PREPROCESS",
                "ASR",
                "ALIGNMENT",
                "DIARIZATION",
                "SPEAKER_EMBEDDING",
                "SPEAKER_MATCHING",
                "TRANSCRIPT_MERGE",
                "RAG_INDEXING"
            );
    }

    @Test
    void createSpeakerEnrollmentTaskAllowsNullMeetingIdAndCarriesProfileMetadata() throws Exception {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        CapturingPublisher publisher = new CapturingPublisher();
        ProcessingTaskApplicationService service = new ProcessingTaskApplicationService(
            tasks,
            new OneMeetingRepository(),
            publisher,
            TenantScopedTransaction.immediate(),
            fixedClock()
        );

        var dto = service.createForSpeakerEnrollment(
            "tenant_01",
            "spk_01",
            "spe_01",
            "file_01",
            "oss://meeting-audio-auska/tenant_01/spe_01.wav",
            "zh",
            "user_01",
            "trace_speaker"
        );

        assertThat(dto.taskType()).isEqualTo("SPEAKER_ENROLLMENT");
        assertThat(dto.meetingId()).isNull();
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(dto.steps()).extracting("stepName")
            .containsExactly(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING);

        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) publisher.events.get(0);
        assertThat(event.meetingId()).isNull();
        assertThat(event.taskType()).isEqualTo("SPEAKER_ENROLLMENT");
        assertThat(event.pipelineSteps()).containsExactly(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING);

        // The outbox preflight (ProcessingTaskMessageValidator) and the
        // contract schema both require expectedInputVersion.chunkStrategyVersion.
        // Without this, the speaker enrollment row gets marked FAILED at
        // the outbox before it ever reaches RabbitMQ.
        @SuppressWarnings("unchecked")
        Map<String, Object> expectedInputVersion =
            (Map<String, Object>) event.payload().get("expectedInputVersion");
        assertThat(expectedInputVersion)
            .as("speaker enrollment payload must carry chunkStrategyVersion to pass the outbox preflight")
            .containsEntry("chunkStrategyVersion", "v1");

        String payloadJson = new ObjectMapper().writeValueAsString(event.payload());
        ProcessingTaskMessageValidator.INSTANCE.validate(payloadJson, new ObjectMapper());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-13T02:00:00Z"), ZoneOffset.UTC);
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    private static final class OneMeetingRepository implements MeetingRepository {
        private final Meeting meeting = Meeting.create("meeting_01", "tenant_01", "Planning", INTERNAL, "zh", List.of(), "user_01");

        @Override
        public Meeting save(Meeting meeting) {
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return tenantId.equals(meeting.tenantId()) && meetingId.equals(meeting.id()) ? Optional.of(meeting) : Optional.empty();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return tenantId.equals(meeting.tenantId()) ? List.of(meeting) : List.of();
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
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return task != null && tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId()) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public java.util.List<ExpiredLease> findExpiredLeases(String tenantId, java.time.OffsetDateTime now, int limit) {
            return java.util.List.of();
        }
    }
}
