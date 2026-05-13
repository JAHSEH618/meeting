package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.internal.callback.CompleteWorkerPhaseCommand;
import com.meeting.api.client.internal.callback.FailTaskCommand;
import com.meeting.api.client.internal.callback.StepCallbackCommand;
import com.meeting.api.client.internal.callback.StepProgressHeartbeatCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.domain.task.CallbackEventRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProcessingTaskCallbackApplicationService {
    private final ProcessingTaskRepository taskRepository;
    private final CallbackEventRepository callbackEventRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final CallbackSecurityVerifier securityVerifier;
    private final Clock clock;

    public ProcessingTaskCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier
    ) {
        this(taskRepository, callbackEventRepository, messagePublisher, tenantScopedTransaction, securityVerifier, Clock.systemUTC());
    }

    public ProcessingTaskCallbackApplicationService(
        ProcessingTaskRepository taskRepository,
        CallbackEventRepository callbackEventRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        CallbackSecurityVerifier securityVerifier,
        Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.callbackEventRepository = callbackEventRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.securityVerifier = securityVerifier;
        this.clock = clock;
    }

    public ProcessingTaskDTO updateStep(StepCallbackCommand command) {
        securityVerifier.verify(command.metadata());
        if (command.status() == StepStatus.RUNNING && command.progress() != null && command.progress() > 0) {
            return heartbeat(new StepProgressHeartbeatCommand(
                command.metadata(),
                command.tenantId(),
                command.meetingId(),
                command.taskId(),
                command.attemptNo(),
                command.stepName(),
                command.progress(),
                OffsetDateTime.now(clock)
            ));
        }
        return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata(), 200, null);
            ProcessingTask task = load(command.tenantId(), command.taskId());
            task.updateWorkerStep(
                command.stepName(),
                command.status(),
                command.progress() == null ? 0 : command.progress(),
                command.attemptNo(),
                command.metadata().leaseOwner(),
                command.metadata().workerId(),
                command.errorCode(),
                OffsetDateTime.now(clock)
            );
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    public ProcessingTaskDTO heartbeat(StepProgressHeartbeatCommand command) {
        securityVerifier.verify(command.metadata());
        return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            ProcessingTask task = load(command.tenantId(), command.taskId());
            task.heartbeat(
                command.stepName(),
                command.progress(),
                command.attemptNo(),
                command.metadata().leaseOwner(),
                command.heartbeatAt(),
                command.heartbeatAt().plusMinutes(5)
            );
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    public ProcessingTaskDTO completeWorkerPhase(CompleteWorkerPhaseCommand command) {
        securityVerifier.verify(command.metadata());
        if (!"WORKER_DAG".equals(command.phase())) {
            throw new IllegalArgumentException("complete phase must be WORKER_DAG");
        }
        return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata(), 200, null);
            ProcessingTask task = load(command.tenantId(), command.taskId());
            var skipped = command.skippedSteps() == null ? java.util.List.<WorkerPhaseCompletedEvent.SkippedStep>of() : command.skippedSteps().stream()
                .map(step -> new WorkerPhaseCompletedEvent.SkippedStep(step.stepName(), step.reason()))
                .toList();
            task.completeWorkerPhase(
                command.status(),
                command.completedSteps() == null ? java.util.List.<ProcessingStep>of() : command.completedSteps(),
                skipped,
                command.attemptNo(),
                command.metadata().leaseOwner(),
                command.finishedAt()
            );
            ProcessingTask saved = taskRepository.save(task);
            messagePublisher.publish(new WorkerPhaseCompletedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                saved.tenantId(),
                saved.taskId(),
                saved.taskType(),
                saved.attemptNo(),
                command.status(),
                command.completedSteps() == null ? java.util.List.of() : command.completedSteps(),
                skipped,
                command.artifactManifestId(),
                0,
                command.finishedAt()
            ));
            return ProcessingTaskAssembler.toDto(saved);
        });
    }

    public ProcessingTaskDTO fail(FailTaskCommand command) {
        securityVerifier.verify(command.metadata());
        return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
            persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata(), 200, command.error().code().name());
            ProcessingTask task = load(command.tenantId(), command.taskId());
            task.updateWorkerStep(
                command.failedStep(),
                StepStatus.FAILED,
                100,
                command.attemptNo(),
                command.metadata().leaseOwner(),
                command.metadata().workerId(),
                command.error().code().name(),
                command.failedAt()
            );
            task.completeTerminal(ProcessingTaskStatus.FAILED, command.error().code().name(), command.failedAt());
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    private ProcessingTask load(String tenantId, String taskId) {
        return taskRepository.findById(tenantId, taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
    }

    private void persistCallbackEvent(String tenantId, String taskId, com.meeting.api.client.internal.callback.CallbackMetadata metadata, int httpStatus, String errorCode) {
        callbackEventRepository.findByIdempotencyKey(tenantId, metadata.idempotencyKey())
            .ifPresent(existing -> {
                if (!existing.bodySha256().equals(metadata.bodySha256())) {
                    throw new IllegalStateException("callback idempotency body hash conflict");
                }
            });
        callbackEventRepository.save(new CallbackEventRepository.CallbackEventRecord(
            tenantId,
            taskId,
            metadata.workerId(),
            metadata.idempotencyKey(),
            metadata.bodySha256(),
            metadata.attemptNo(),
            metadata.leaseOwner(),
            "",
            httpStatus,
            errorCode,
            metadata.traceId(),
            OffsetDateTime.now(clock)
        ));
    }
}
