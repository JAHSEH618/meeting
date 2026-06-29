package com.meeting.api.domain.task;

/**
 * Re-dispatches the worker task message for an orphaned {@link ProcessingTask}
 * whose lease expired and was requeued.
 *
 * <p>The original message payload (audioUri, channelMap, expectedInputVersion,
 * …) is not held on the aggregate — it is assembled once at task-creation time.
 * Implementations recover it (e.g. from the transactional outbox) and re-publish
 * it carrying the task's new attempt number so the worker's callbacks pass the
 * attempt/lease fencing.
 */
public interface OrphanedTaskRepublisher {

    /**
     * Re-publish the worker message for {@code taskId} with {@code newAttemptNo}.
     *
     * <p>Must run in the caller's transaction so the re-publish and the task's
     * requeue commit atomically.
     *
     * @return {@code true} if a message was re-published; {@code false} if no
     *     original payload could be recovered (the caller should then leave the
     *     task untouched so the next scan retries).
     */
    boolean republish(String tenantId, String taskId, int newAttemptNo);
}
