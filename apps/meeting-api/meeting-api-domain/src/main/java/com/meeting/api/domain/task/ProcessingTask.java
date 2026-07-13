package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProcessingTask {
    /** Total attempt budget shared by lease-expiry requeues and worker-reported retryable failures. */
    public static final int MAX_ATTEMPTS = 3;

    private final String taskId;
    private final String tenantId;
    private final String meetingId;
    private final String taskType;
    private final Map<ProcessingStep, ProcessingTaskStep> steps;
    private ProcessingTaskStatus status;
    private ProcessingTaskPhase phase;
    private int attemptNo;
    private String currentStep;
    private String lastErrorCode;
    private boolean retryable;
    private String leaseOwner;
    private OffsetDateTime leaseExpiresAt;
    private OffsetDateTime heartbeatAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private boolean holdAtWorkerPhase;

    private ProcessingTask(
        String taskId,
        String tenantId,
        String meetingId,
        String taskType,
        ProcessingTaskStatus status,
        ProcessingTaskPhase phase,
        int attemptNo,
        List<ProcessingTaskStep> steps,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        this.taskId = requireText(taskId, "taskId");
        this.tenantId = requireText(tenantId, "tenantId");
        this.meetingId = meetingId;
        this.taskType = requireText(taskType, "taskType");
        this.status = Objects.requireNonNull(status, "status");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.attemptNo = attemptNo;
        this.steps = new LinkedHashMap<>();
        for (ProcessingTaskStep step : steps) {
            this.steps.put(step.stepName(), step);
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.holdAtWorkerPhase = false;
    }

    public static ProcessingTask create(
        String taskId,
        String tenantId,
        String meetingId,
        String taskType,
        List<ProcessingStep> stepNames,
        OffsetDateTime now
    ) {
        return create(taskId, tenantId, meetingId, taskType, stepNames, now, false);
    }

    public static ProcessingTask create(
        String taskId,
        String tenantId,
        String meetingId,
        String taskType,
        List<ProcessingStep> stepNames,
        OffsetDateTime now,
        boolean holdAtWorkerPhase
    ) {
        requireText(taskType, "taskType");
        Objects.requireNonNull(stepNames, "stepNames");
        if (stepNames.isEmpty()) {
            throw new IllegalArgumentException("stepNames must not be empty");
        }
        List<ProcessingTaskStep> steps = stepNames.stream()
            .map(step -> ProcessingTaskStep.pending(step, defaultSourceFor(step)))
            .toList();
        ProcessingTask task = new ProcessingTask(
            taskId,
            tenantId,
            meetingId,
            taskType,
            ProcessingTaskStatus.PENDING,
            ProcessingTaskPhase.WORKER_DAG_RUNNING,
            1,
            steps,
            now,
            now
        );
        task.holdAtWorkerPhase = holdAtWorkerPhase;
        return task;
    }

    public static ProcessingTask restore(
        String taskId,
        String tenantId,
        String meetingId,
        String taskType,
        ProcessingTaskStatus status,
        ProcessingTaskPhase phase,
        int attemptNo,
        String currentStep,
        String lastErrorCode,
        boolean retryable,
        String leaseOwner,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime heartbeatAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ProcessingTaskStep> steps
    ) {
        return restore(taskId, tenantId, meetingId, taskType, status, phase, attemptNo,
            currentStep, lastErrorCode, retryable, leaseOwner, leaseExpiresAt,
            heartbeatAt, createdAt, updatedAt, steps, false);
    }

    public static ProcessingTask restore(
        String taskId,
        String tenantId,
        String meetingId,
        String taskType,
        ProcessingTaskStatus status,
        ProcessingTaskPhase phase,
        int attemptNo,
        String currentStep,
        String lastErrorCode,
        boolean retryable,
        String leaseOwner,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime heartbeatAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ProcessingTaskStep> steps,
        boolean holdAtWorkerPhase
    ) {
        ProcessingTask task = new ProcessingTask(
            taskId,
            tenantId,
            meetingId,
            taskType,
            status,
            phase,
            attemptNo,
            steps,
            createdAt,
            updatedAt
        );
        task.currentStep = currentStep;
        task.lastErrorCode = lastErrorCode;
        task.retryable = retryable;
        task.leaseOwner = leaseOwner;
        task.leaseExpiresAt = leaseExpiresAt;
        task.heartbeatAt = heartbeatAt;
        task.holdAtWorkerPhase = holdAtWorkerPhase;
        return task;
    }

    public void enqueue(OffsetDateTime now) {
        requireStatus(ProcessingTaskStatus.PENDING);
        requireNonTerminal();
        status = ProcessingTaskStatus.QUEUED;
        touch(now);
    }

    public void claimLease(String workerId, String leaseOwner, OffsetDateTime leaseExpiresAt, OffsetDateTime now) {
        if (status != ProcessingTaskStatus.QUEUED && status != ProcessingTaskStatus.RUNNING) {
            throw new IllegalStateException("task must be QUEUED or RUNNING to claim lease");
        }
        requireNonTerminal();
        this.status = ProcessingTaskStatus.RUNNING;
        this.leaseOwner = requireText(leaseOwner, "leaseOwner");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        this.heartbeatAt = now;
        currentWorkerStep().ifPresent(step -> {
            currentStep = step.stepName().name();
            step.markRunning(0, attemptNo, leaseOwner, workerId, now);
        });
        touch(now);
    }

    public void markJavaStepSucceeded(ProcessingStep stepName, OffsetDateTime now) {
        requireNonTerminal();
        ProcessingTaskStep step = requireJavaStep(stepName);
        // attempt_count is part of the persisted step's unique key; if we
        // drop it back to null here while markJavaStepRunning recorded
        // task.attemptNo, the next save() lands on a different attempt and
        // the old RUNNING row survives -> completeJavaPhase refuses to close.
        step.markSucceeded(100, attemptNo, null, null, now);
        currentStep = stepName.name();
        touch(now);
    }

    public void markJavaStepRunning(ProcessingStep stepName, int progress, OffsetDateTime now) {
        requireNonTerminal();
        ProcessingTaskStep step = requireJavaStep(stepName);
        step.markRunning(progress, attemptNo, null, null, now);
        currentStep = stepName.name();
        touch(now);
    }

    public void markJavaStepFailed(ProcessingStep stepName, String errorCode, OffsetDateTime now) {
        requireNonTerminal();
        ProcessingTaskStep step = requireJavaStep(stepName);
        step.markFailed(100, attemptNo, null, null, errorCode, now);
        currentStep = stepName.name();
        lastErrorCode = errorCode;
        retryable = true;
        touch(now);
    }

    public void completeJavaPhase(OffsetDateTime now) {
        requireStatus(ProcessingTaskStatus.RUNNING);
        if (phase != ProcessingTaskPhase.JAVA_LLM_RUNNING) {
            throw new IllegalStateException("Java phase can only complete from JAVA_LLM_RUNNING");
        }
        for (ProcessingTaskStep step : steps.values()) {
            if (step.source() == ProcessingStepUpdateSource.JAVA_TASK_SERVICE) {
                StepStatus s = step.status();
                if (s == StepStatus.PENDING || s == StepStatus.QUEUED || s == StepStatus.RUNNING) {
                    throw new IllegalStateException("Java step still in progress: " + step.stepName());
                }
            }
        }
        boolean anyFailed = steps.values().stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        boolean summaryFailed = steps.values().stream().anyMatch(s ->
            s.stepName() == ProcessingStep.SUMMARY && s.status() == StepStatus.FAILED
        );
        boolean anySkipped = steps.values().stream().anyMatch(s -> s.status() == StepStatus.SKIPPED);
        ProcessingTaskStatus terminalStatus;
        String terminalErrorCode = null;
        if (summaryFailed) {
            terminalStatus = ProcessingTaskStatus.FAILED;
            terminalErrorCode = steps.values().stream()
                .filter(s -> s.status() == StepStatus.FAILED)
                .map(ProcessingTaskStep::errorCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(lastErrorCode);
        } else if (anyFailed || anySkipped) {
            terminalStatus = ProcessingTaskStatus.PARTIAL_SUCCEEDED;
            terminalErrorCode = steps.values().stream()
                .filter(s -> s.status() == StepStatus.FAILED)
                .map(ProcessingTaskStep::errorCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(lastErrorCode);
        } else {
            terminalStatus = ProcessingTaskStatus.SUCCEEDED;
        }
        completeTerminal(terminalStatus, terminalErrorCode, now);
    }

    public boolean hasPendingJavaSteps() {
        return steps.values().stream().anyMatch(step ->
            step.source() == ProcessingStepUpdateSource.JAVA_TASK_SERVICE
                && (step.status() == StepStatus.PENDING || step.status() == StepStatus.QUEUED || step.status() == StepStatus.RUNNING)
        );
    }

    private ProcessingTaskStep requireJavaStep(ProcessingStep stepName) {
        ProcessingTaskStep step = step(stepName);
        if (step.source() != ProcessingStepUpdateSource.JAVA_TASK_SERVICE) {
            throw new IllegalArgumentException("step is not owned by Java task service: " + stepName);
        }
        return step;
    }

    private ProcessingTaskStep requireWorkerStep(ProcessingStep stepName) {
        ProcessingTaskStep step = step(stepName);
        if (step.source() != ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
            throw new IllegalArgumentException("step is not owned by ai-worker callback: " + stepName);
        }
        return step;
    }

    public void updateWorkerStep(
        ProcessingStep stepName,
        StepStatus newStatus,
        int progress,
        int callbackAttemptNo,
        String callbackLeaseOwner,
        String workerId,
        String errorCode,
        OffsetDateTime now
    ) {
        validateCallback(callbackAttemptNo, callbackLeaseOwner);
        requireNonTerminal();
        ProcessingTaskStep step = step(stepName);
        if (step.source() != ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
            throw new IllegalArgumentException("step is not owned by ai-worker callback: " + stepName);
        }
        currentStep = stepName.name();
        switch (newStatus) {
            case RUNNING -> step.markRunning(progress, attemptNo, callbackLeaseOwner, workerId, now);
            case SUCCEEDED -> step.markSucceeded(progress, attemptNo, callbackLeaseOwner, workerId, now);
            case FAILED -> {
                step.markFailed(progress, attemptNo, callbackLeaseOwner, workerId, errorCode, now);
                lastErrorCode = errorCode;
                retryable = true;
            }
            case SKIPPED -> step.markSkipped(progress, attemptNo, callbackLeaseOwner, workerId, errorCode, now);
            default -> throw new IllegalArgumentException("unsupported callback step status: " + newStatus);
        }
        touch(now);
    }

    public void heartbeat(
        ProcessingStep stepName,
        int progress,
        int callbackAttemptNo,
        String callbackLeaseOwner,
        OffsetDateTime heartbeatAt,
        OffsetDateTime leaseExpiresAt
    ) {
        validateCallback(callbackAttemptNo, callbackLeaseOwner);
        requireStatus(ProcessingTaskStatus.RUNNING);
        requireNonTerminal();
        ProcessingTaskStep step = step(stepName);
        if (step.source() != ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
            throw new IllegalArgumentException("heartbeat step is not owned by ai-worker callback: " + stepName);
        }
        if (progress <= 0) {
            throw new IllegalArgumentException("heartbeat progress must be positive");
        }
        this.heartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        currentStep = stepName.name();
        step.heartbeat(progress, callbackAttemptNo, callbackLeaseOwner, heartbeatAt);
        touch(heartbeatAt);
    }

    public void completeWorkerPhase(
        ProcessingTaskStatus workerStatus,
        List<ProcessingStep> completedSteps,
        List<WorkerPhaseCompletedEvent.SkippedStep> skippedSteps,
        int callbackAttemptNo,
        String callbackLeaseOwner,
        OffsetDateTime now
    ) {
        validateCallback(callbackAttemptNo, callbackLeaseOwner);
        requireStatus(ProcessingTaskStatus.RUNNING);
        if (phase != ProcessingTaskPhase.WORKER_DAG_RUNNING) {
            throw new IllegalStateException("worker phase can only complete from WORKER_DAG_RUNNING");
        }
        if (workerStatus != ProcessingTaskStatus.SUCCEEDED && workerStatus != ProcessingTaskStatus.PARTIAL_SUCCEEDED) {
            throw new IllegalArgumentException("workerStatus must be SUCCEEDED or PARTIAL_SUCCEEDED");
        }
        List<ProcessingStep> completed = completedSteps == null ? List.<ProcessingStep>of() : completedSteps;
        List<WorkerPhaseCompletedEvent.SkippedStep> skipped =
            skippedSteps == null ? List.<WorkerPhaseCompletedEvent.SkippedStep>of() : skippedSteps;
        for (ProcessingStep completedStep : completed) {
            requireWorkerStep(completedStep);
        }
        for (WorkerPhaseCompletedEvent.SkippedStep skippedStep : skipped) {
            requireWorkerStep(skippedStep.stepName());
        }
        for (ProcessingStep completedStep : completed) {
            step(completedStep).markSucceeded(100, callbackAttemptNo, callbackLeaseOwner, null, now);
        }
        for (WorkerPhaseCompletedEvent.SkippedStep skippedStep : skipped) {
            step(skippedStep.stepName()).markSkipped(100, callbackAttemptNo, callbackLeaseOwner, null, skippedStep.reason(), now);
        }
        phase = ProcessingTaskPhase.WORKER_DAG_DONE;
        status = ProcessingTaskStatus.RUNNING;
        leaseOwner = null;
        leaseExpiresAt = null;
        touch(now);
    }

    public void beginJavaLlm(OffsetDateTime now) {
        requireStatus(ProcessingTaskStatus.RUNNING);
        if (phase != ProcessingTaskPhase.WORKER_DAG_DONE) {
            throw new IllegalStateException("Java LLM phase can only start after worker DAG is done");
        }
        phase = ProcessingTaskPhase.JAVA_LLM_RUNNING;
        touch(now);
    }

    public void completeTerminal(ProcessingTaskStatus terminalStatus, String errorCode, OffsetDateTime now) {
        if (!isTerminalStatus(terminalStatus)) {
            throw new IllegalArgumentException("terminalStatus is not terminal: " + terminalStatus);
        }
        status = terminalStatus;
        phase = ProcessingTaskPhase.TERMINAL;
        lastErrorCode = errorCode;
        retryable = terminalStatus == ProcessingTaskStatus.FAILED || terminalStatus == ProcessingTaskStatus.PARTIAL_SUCCEEDED;
        leaseOwner = null;
        leaseExpiresAt = null;
        touch(now);
    }

    public boolean markOrphanedIfLeaseExpired(OffsetDateTime now) {
        if (phase == ProcessingTaskPhase.TERMINAL || status != ProcessingTaskStatus.RUNNING || leaseExpiresAt == null) {
            return false;
        }
        if (leaseExpiresAt.isAfter(now)) {
            return false;
        }
        status = ProcessingTaskStatus.ORPHANED;
        leaseOwner = null;
        leaseExpiresAt = null;
        touch(now);
        return true;
    }

    /** Whether another attempt may still be dispatched without exhausting the retry budget. */
    public boolean hasRetryBudget() {
        return attemptNo < MAX_ATTEMPTS;
    }

    /**
     * Transition to ORPHANED because the worker reported a retryable failure,
     * so the attempt can be requeued via {@link #requeueOrphaned} immediately
     * instead of waiting for the lease to expire.
     */
    public void markOrphanedForRetryableFailure(OffsetDateTime now) {
        requireNonTerminal();
        if (status != ProcessingTaskStatus.RUNNING && status != ProcessingTaskStatus.QUEUED) {
            throw new IllegalStateException("retryable failure requeue requires RUNNING or QUEUED but was " + status);
        }
        status = ProcessingTaskStatus.ORPHANED;
        leaseOwner = null;
        leaseExpiresAt = null;
        touch(now);
    }

    public void requeueOrphaned(OffsetDateTime now) {
        requireStatus(ProcessingTaskStatus.ORPHANED);
        requireNonTerminal();
        attemptNo += 1;
        if (attemptNo > MAX_ATTEMPTS) {
            throw new IllegalStateException("retry exhausted: attemptNo=" + attemptNo);
        }
        status = ProcessingTaskStatus.QUEUED;
        retryable = false;
        for (ProcessingTaskStep step : steps.values()) {
            if (step.source() == ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
                step.resetForAttempt();
            }
        }
        touch(now);
    }

    public void requestCancel(OffsetDateTime now) {
        if (phase == ProcessingTaskPhase.TERMINAL) {
            throw new IllegalStateException("terminal task cannot be cancelled");
        }
        if (status != ProcessingTaskStatus.PENDING
            && status != ProcessingTaskStatus.QUEUED
            && status != ProcessingTaskStatus.RUNNING
            && status != ProcessingTaskStatus.ORPHANED) {
            throw new IllegalStateException("task cannot be cancelled from status " + status);
        }
        status = ProcessingTaskStatus.CANCEL_PENDING;
        touch(now);
    }

    public void confirmCancelled(OffsetDateTime now) {
        requireStatus(ProcessingTaskStatus.CANCEL_PENDING);
        completeTerminal(ProcessingTaskStatus.CANCELLED, null, now);
        for (ProcessingTaskStep step : steps.values()) {
            if (!step.status().name().equals(StepStatus.SUCCEEDED.name())) {
                step.markCancelled(now);
            }
        }
    }

    public void retry(OffsetDateTime now) {
        if (phase != ProcessingTaskPhase.TERMINAL
            || (status != ProcessingTaskStatus.FAILED && status != ProcessingTaskStatus.PARTIAL_SUCCEEDED)) {
            throw new IllegalStateException("only failed or partial terminal tasks can be retried");
        }
        attemptNo += 1;
        status = ProcessingTaskStatus.QUEUED;
        phase = ProcessingTaskPhase.WORKER_DAG_RUNNING;
        currentStep = null;
        lastErrorCode = null;
        retryable = false;
        leaseOwner = null;
        leaseExpiresAt = null;
        heartbeatAt = null;
        for (ProcessingTaskStep step : steps.values()) {
            if (step.source() == ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
                step.resetForAttempt();
            }
        }
        touch(now);
    }

    public ProcessingTaskStep step(ProcessingStep stepName) {
        ProcessingTaskStep step = steps.get(stepName);
        if (step == null) {
            throw new IllegalArgumentException("unknown task step: " + stepName);
        }
        return step;
    }

    public List<ProcessingTaskStep> steps() {
        return Collections.unmodifiableList(new ArrayList<>(steps.values()));
    }

    public String taskId() { return taskId; }
    public String tenantId() { return tenantId; }
    public String meetingId() { return meetingId; }
    public String taskType() { return taskType; }
    public ProcessingTaskStatus status() { return status; }
    public ProcessingTaskPhase phase() { return phase; }
    public int attemptNo() { return attemptNo; }
    public String currentStep() { return currentStep; }
    public String lastErrorCode() { return lastErrorCode; }
    public boolean retryable() { return retryable; }
    public String leaseOwner() { return leaseOwner; }
    public OffsetDateTime leaseExpiresAt() { return leaseExpiresAt; }
    public OffsetDateTime heartbeatAt() { return heartbeatAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public boolean holdAtWorkerPhase() { return holdAtWorkerPhase; }

    private java.util.Optional<ProcessingTaskStep> currentWorkerStep() {
        return steps.values().stream()
            .filter(step -> step.source() == ProcessingStepUpdateSource.AI_WORKER_CALLBACK)
            .filter(step -> step.status() == StepStatus.PENDING || step.status() == StepStatus.QUEUED)
            .findFirst();
    }

    private void validateCallback(int callbackAttemptNo, String callbackLeaseOwner) {
        if (callbackAttemptNo != attemptNo) {
            throw new IllegalStateException("callback attempt does not match current attempt");
        }
        if (!Objects.equals(leaseOwner, callbackLeaseOwner)) {
            throw new IllegalStateException("callback lease owner does not match current lease");
        }
    }

    private void requireStatus(ProcessingTaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected status " + expected + " but was " + status);
        }
    }

    private void requireNonTerminal() {
        if (phase == ProcessingTaskPhase.TERMINAL || isTerminalStatus(status)) {
            throw new IllegalStateException("task is terminal");
        }
    }

    private void touch(OffsetDateTime now) {
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static boolean isTerminalStatus(ProcessingTaskStatus status) {
        return status == ProcessingTaskStatus.SUCCEEDED
            || status == ProcessingTaskStatus.PARTIAL_SUCCEEDED
            || status == ProcessingTaskStatus.FAILED
            || status == ProcessingTaskStatus.CANCELLED;
    }

    private static ProcessingStepUpdateSource defaultSourceFor(ProcessingStep step) {
        return switch (step) {
            case AUDIO_UPLOAD, SUMMARY, EXTRACTION, EXPORT -> ProcessingStepUpdateSource.JAVA_TASK_SERVICE;
            default -> ProcessingStepUpdateSource.AI_WORKER_CALLBACK;
        };
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
