package com.meeting.api.app.task;

import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.client.task.ProcessingTaskStepDTO;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskStep;

final class ProcessingTaskAssembler {
    private ProcessingTaskAssembler() {
    }

    static ProcessingTaskDTO toDto(ProcessingTask task) {
        return new ProcessingTaskDTO(
            task.taskId(),
            task.tenantId(),
            task.meetingId(),
            task.taskType(),
            task.status(),
            task.phase(),
            task.attemptNo(),
            task.currentStep(),
            task.lastErrorCode(),
            task.retryable(),
            null,
            task.leaseExpiresAt(),
            task.createdAt(),
            task.updatedAt(),
            task.steps().stream().map(ProcessingTaskAssembler::toStepDto).toList()
        );
    }

    static ProcessingTaskStepDTO toStepDto(ProcessingTaskStep step) {
        return new ProcessingTaskStepDTO(
            step.stepName(),
            step.status(),
            step.progress(),
            step.startedAt(),
            step.finishedAt(),
            step.heartbeatAt(),
            step.attemptNo(),
            step.leaseOwner(),
            step.workerId(),
            step.retryable(),
            step.errorCode(),
            step.source()
        );
    }
}
