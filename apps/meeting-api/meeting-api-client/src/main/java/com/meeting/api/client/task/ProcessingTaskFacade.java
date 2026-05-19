package com.meeting.api.client.task;

import java.util.Optional;

public interface ProcessingTaskFacade {
    ProcessingTaskDTO create(CreateProcessingTaskCommand command);

    Optional<ProcessingTaskDTO> get(String tenantId, String taskId);

    Optional<ProcessingTaskDTO> getLatestForMeeting(String tenantId, String meetingId);

    ProcessingTaskDTO retry(RetryTaskCommand command);

    ProcessingTaskDTO cancel(CancelTaskCommand command);

    /**
     * Promote a task that has been halted at WORKER_DAG_DONE (created with
     * {@code holdAtWorkerPhase=true}) into the Java LLM phase. Idempotent on
     * JAVA_LLM_RUNNING / TERMINAL. Throws INVALID_TASK_PHASE if the task is
     * not yet at WORKER_DAG_DONE.
     */
    ProcessingTaskDTO resumeJavaPhase(ResumeJavaPhaseCommand command);
}
