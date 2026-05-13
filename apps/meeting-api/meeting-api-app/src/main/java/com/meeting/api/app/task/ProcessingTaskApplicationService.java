package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.task.CancelTaskCommand;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.client.task.ProcessingTaskFacade;
import com.meeting.api.client.task.RetryTaskCommand;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProcessingTaskApplicationService implements ProcessingTaskFacade {
    public static final String MEETING_FULL_PIPELINE = "MEETING_FULL_PIPELINE";

    private static final List<ProcessingStep> MVP0_STEPS = List.of(
        ProcessingStep.AUDIO_UPLOAD,
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.DIARIZATION,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING
    );

    private static final List<ProcessingStep> MVP0_WORKER_STEPS = List.of(
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.DIARIZATION,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING
    );

    private final ProcessingTaskRepository taskRepository;
    private final MeetingRepository meetingRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(taskRepository, meetingRepository, messagePublisher, tenantScopedTransaction, Clock.systemUTC());
    }

    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.meetingRepository = meetingRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public ProcessingTaskDTO create(CreateProcessingTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            if (!MEETING_FULL_PIPELINE.equals(command.taskType())) {
                throw new IllegalArgumentException("unsupported taskType: " + command.taskType());
            }
            meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + command.meetingId()));

            OffsetDateTime now = OffsetDateTime.now(clock);
            ProcessingTask task = ProcessingTask.create(
                "task_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.meetingId(),
                command.taskType(),
                MVP0_STEPS,
                now
            );
            task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, now);
            task.enqueue(now);
            task.claimLease(
                "worker_dev_001",
                "worker_dev_001:" + task.taskId() + ":" + task.attemptNo(),
                now.plusMinutes(5),
                now
            );
            ProcessingTask saved = taskRepository.save(task);
            messagePublisher.publish(new ProcessingTaskCreatedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                saved.tenantId(),
                saved.taskId(),
                saved.meetingId(),
                saved.taskType(),
                saved.attemptNo(),
                MVP0_WORKER_STEPS,
                0,
                now,
                processingTaskMessagePayload(command, saved)
            ));
            return ProcessingTaskAssembler.toDto(saved);
        });
    }

    @Override
    public Optional<ProcessingTaskDTO> get(String tenantId, String taskId) {
        return taskRepository.findById(tenantId, taskId).map(ProcessingTaskAssembler::toDto);
    }

    @Override
    public ProcessingTaskDTO retry(RetryTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            task.retry(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    @Override
    public ProcessingTaskDTO cancel(CancelTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            task.requestCancel(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    private Map<String, Object> processingTaskMessagePayload(CreateProcessingTaskCommand command, ProcessingTask task) {
        return Map.of(
            "taskId", task.taskId(),
            "taskType", task.taskType(),
            "tenantId", task.tenantId(),
            "meetingId", task.meetingId(),
            "securityLevel", "INTERNAL",
            "attemptNo", task.attemptNo(),
            "pipelineSteps", MVP0_WORKER_STEPS.stream().map(Enum::name).toList(),
            "expectedInputVersion", command.expectedInputVersion() == null ? Map.of("chunkStrategyVersion", "v1") : command.expectedInputVersion(),
            "options", command.options() == null ? Map.of() : command.options(),
            "traceId", command.traceId() == null ? "" : command.traceId()
        );
    }
}
