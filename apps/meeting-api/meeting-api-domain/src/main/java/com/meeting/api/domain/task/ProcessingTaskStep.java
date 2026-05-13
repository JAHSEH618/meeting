package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.StepStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class ProcessingTaskStep {
    private final ProcessingStep stepName;
    private final ProcessingStepUpdateSource source;
    private StepStatus status;
    private int progress;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime heartbeatAt;
    private Integer attemptNo;
    private String leaseOwner;
    private String workerId;
    private Boolean retryable;
    private String errorCode;

    private ProcessingTaskStep(ProcessingStep stepName, StepStatus status, int progress, ProcessingStepUpdateSource source) {
        this.stepName = Objects.requireNonNull(stepName, "stepName");
        this.status = Objects.requireNonNull(status, "status");
        this.progress = progress;
        this.source = Objects.requireNonNull(source, "source");
    }

    public static ProcessingTaskStep pending(ProcessingStep stepName, ProcessingStepUpdateSource source) {
        return new ProcessingTaskStep(stepName, StepStatus.PENDING, 0, source);
    }

    void markRunning(int progress, int attemptNo, String leaseOwner, String workerId, OffsetDateTime now) {
        requireProgress(progress);
        if (status == StepStatus.SUCCEEDED || status == StepStatus.FAILED || status == StepStatus.SKIPPED || status == StepStatus.CANCELLED) {
            throw new IllegalStateException("terminal step cannot run again: " + stepName);
        }
        status = StepStatus.RUNNING;
        this.progress = progress;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.workerId = workerId;
        if (startedAt == null) {
            startedAt = now;
        }
        heartbeatAt = now;
    }

    void markSucceeded(int progress, Integer attemptNo, String leaseOwner, String workerId, OffsetDateTime now) {
        requireProgress(progress);
        status = StepStatus.SUCCEEDED;
        this.progress = progress;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.workerId = workerId;
        if (startedAt == null) {
            startedAt = now;
        }
        finishedAt = now;
        errorCode = null;
        retryable = false;
    }

    void markFailed(int progress, Integer attemptNo, String leaseOwner, String workerId, String errorCode, OffsetDateTime now) {
        requireProgress(progress);
        status = StepStatus.FAILED;
        this.progress = progress;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.workerId = workerId;
        this.errorCode = errorCode;
        this.retryable = true;
        if (startedAt == null) {
            startedAt = now;
        }
        finishedAt = now;
    }

    void markSkipped(int progress, Integer attemptNo, String leaseOwner, String workerId, String reason, OffsetDateTime now) {
        requireProgress(progress);
        status = StepStatus.SKIPPED;
        this.progress = progress;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.workerId = workerId;
        this.errorCode = reason;
        this.retryable = false;
        if (startedAt == null) {
            startedAt = now;
        }
        finishedAt = now;
    }

    void heartbeat(int progress, int attemptNo, String leaseOwner, OffsetDateTime heartbeatAt) {
        requireProgress(progress);
        if (status != StepStatus.RUNNING) {
            status = StepStatus.RUNNING;
            if (startedAt == null) {
                startedAt = heartbeatAt;
            }
        }
        this.progress = progress;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.heartbeatAt = heartbeatAt;
    }

    void markCancelled(OffsetDateTime now) {
        status = StepStatus.CANCELLED;
        finishedAt = now;
        retryable = false;
    }

    void resetForAttempt() {
        status = StepStatus.PENDING;
        progress = 0;
        startedAt = null;
        finishedAt = null;
        heartbeatAt = null;
        attemptNo = null;
        leaseOwner = null;
        workerId = null;
        retryable = null;
        errorCode = null;
    }

    public ProcessingStep stepName() { return stepName; }
    public StepStatus status() { return status; }
    public int progress() { return progress; }
    public OffsetDateTime startedAt() { return startedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }
    public OffsetDateTime heartbeatAt() { return heartbeatAt; }
    public Integer attemptNo() { return attemptNo; }
    public String leaseOwner() { return leaseOwner; }
    public String workerId() { return workerId; }
    public Boolean retryable() { return retryable; }
    public String errorCode() { return errorCode; }
    public ProcessingStepUpdateSource source() { return source; }

    private static void requireProgress(int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be 0..100");
        }
    }
}
