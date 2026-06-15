package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.WorkerDagDoneRecoveryScanner;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerDagDoneRecoveryScannerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @Test
    void scansAndRecoversStuckWorkerDagDoneTasks() {
        ProcessingTask task1 = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        ProcessingTask task2 = stuckTask("task_02", "tenant_02", "meeting_02", "MEETING_FULL_PIPELINE");
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task1, task2));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );

        scanner.scanAndRecover();

        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.get(0).taskId()).isEqualTo("task_01");
        assertThat(publisher.events.get(0).tenantId()).isEqualTo("tenant_01");
        assertThat(publisher.events.get(0).taskType()).isEqualTo("MEETING_FULL_PIPELINE");
        assertThat(publisher.events.get(1).taskId()).isEqualTo("task_02");
        assertThat(publisher.events.get(1).tenantId()).isEqualTo("tenant_02");
    }

    @Test
    void skipsTerminalTasks() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        task.completeTerminal(ProcessingTaskStatus.SUCCEEDED, null, NOW);
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );

        scanner.scanAndRecover();

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void skipsWorkerDagRunningTasks() {
        // Create a task that's still in WORKER_DAG_RUNNING phase
        List<ProcessingStep> steps = List.of(
            ProcessingStep.AUDIO_UPLOAD,
            ProcessingStep.AUDIO_PREPROCESS,
            ProcessingStep.ASR,
            ProcessingStep.TRANSCRIPT_MERGE,
            ProcessingStep.SUMMARY,
            ProcessingStep.EXTRACTION
        );
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE", steps, NOW, false);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(10), NOW);
        // Task is RUNNING but still in WORKER_DAG_RUNNING phase (has not completed worker phase)

        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );

        scanner.scanAndRecover();

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void skipsJavaLlmRunningTasks() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        task.beginJavaLlm(NOW);
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );

        scanner.scanAndRecover();

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void handlesPartialSucceeded() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        // Simulate worker phase completing with PARTIAL_SUCCEEDED but not progressing to Java phase
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            CLOCK
        );

        scanner.scanAndRecover();

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0).taskId()).isEqualTo("task_01");
    }

    private static ProcessingTask stuckTask(String taskId, String tenantId, String meetingId, String taskType) {
        List<ProcessingStep> steps = List.of(
            ProcessingStep.AUDIO_UPLOAD,
            ProcessingStep.AUDIO_PREPROCESS,
            ProcessingStep.ASR,
            ProcessingStep.TRANSCRIPT_MERGE,
            ProcessingStep.SUMMARY,
            ProcessingStep.EXTRACTION
        );
        ProcessingTask task = ProcessingTask.create(taskId, tenantId, meetingId, taskType, steps, NOW, false);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:" + taskId + ":1", NOW.plusMinutes(5), NOW);
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(),
            1,
            "worker_01:" + taskId + ":1",
            NOW.plusMinutes(1)
        );
        return task;
    }

    private static class InMemoryTaskRepository implements ProcessingTaskRepository, WorkerDagDoneRecoveryScanner.ProcessingTaskRepositoryExtensions {
        private final List<ProcessingTask> tasks;

        InMemoryTaskRepository(List<ProcessingTask> tasks) {
            this.tasks = new ArrayList<>(tasks);
        }

        @Override
        public ProcessingTask save(ProcessingTask task) {
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tasks.stream()
                .filter(t -> t.tenantId().equals(tenantId) && t.taskId().equals(taskId))
                .findFirst();
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tasks.stream()
                .filter(t -> t.tenantId().equals(tenantId) && meetingId.equals(t.meetingId()))
                .findFirst();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }

        @Override
        public List<ProcessingTask> findStuckWorkerDagDone(int limit) {
            return tasks.stream()
                .filter(t -> t.phase() == ProcessingTaskPhase.WORKER_DAG_DONE)
                .filter(t -> t.status() != ProcessingTaskStatus.SUCCEEDED
                    && t.status() != ProcessingTaskStatus.FAILED
                    && t.status() != ProcessingTaskStatus.CANCELLED
                    && t.status() != ProcessingTaskStatus.PARTIAL_SUCCEEDED)
                .limit(limit)
                .toList();
        }
    }

    private static class RecordingEventPublisher implements ApplicationEventPublisher {
        final List<WorkerPhaseCompletedEvent> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            if (event instanceof WorkerPhaseCompletedEvent e) {
                events.add(e);
            }
        }
    }
}
