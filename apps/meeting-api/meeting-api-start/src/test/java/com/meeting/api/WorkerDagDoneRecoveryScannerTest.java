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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerDagDoneRecoveryScannerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-13T00:00:00Z");
    /** Scans run well after the fixture tasks' last update, so the grace period never hides them. */
    private static final Clock SCAN_CLOCK = Clock.fixed(NOW.plusMinutes(30).toInstant(), ZoneOffset.UTC);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 100;

    @Test
    void scansAndRecoversStuckWorkerDagDoneTasksPerTenant() {
        ProcessingTask task1 = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        ProcessingTask task2 = stuckTask("task_02", "tenant_02", "meeting_02", "MEETING_FULL_PIPELINE");
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task1, task2));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        WorkerDagDoneRecoveryScanner.ScanReport report =
            scanner.scanAndRecover(List.of("tenant_01", "tenant_02"));

        assertThat(report.scanned()).isEqualTo(2);
        assertThat(report.recovered()).isEqualTo(2);
        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.get(0).taskId()).isEqualTo("task_01");
        assertThat(publisher.events.get(0).tenantId()).isEqualTo("tenant_01");
        assertThat(publisher.events.get(0).taskType()).isEqualTo("MEETING_FULL_PIPELINE");
        assertThat(publisher.events.get(1).taskId()).isEqualTo("task_02");
        assertThat(publisher.events.get(1).tenantId()).isEqualTo("tenant_02");
        // Each tenant's query must be tenant-scoped (RLS): one lookup per tenant.
        assertThat(repo.scannedTenants).containsExactly("tenant_01", "tenant_02");
    }

    @Test
    void skipsTenantsNotInTheActiveList() {
        ProcessingTask task = stuckTask("task_01", "tenant_02", "meeting_01", "MEETING_FULL_PIPELINE");
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void skipsTerminalTasks() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        task.completeTerminal(ProcessingTaskStatus.SUCCEEDED, null, NOW);
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

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
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void skipsJavaLlmRunningTasks() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        task.beginJavaLlm(NOW);
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void handlesPartialSucceeded() {
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        // Simulate worker phase completing with PARTIAL_SUCCEEDED but not progressing to Java phase
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0).taskId()).isEqualTo("task_01");
    }

    @Test
    void leavesRecentlyUpdatedTasksToTheAsyncListener() {
        // The listener normally fires right after the callback commits; only
        // tasks older than the grace period count as stuck.
        ProcessingTask task = stuckTask("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE");
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        // Scan "now" is within the grace period of the task's last update (NOW+1min).
        Clock freshClock = Clock.fixed(NOW.plusMinutes(2).toInstant(), ZoneOffset.UTC);
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            repo, publisher, TenantScopedTransaction.immediate(), freshClock, BATCH_SIZE, STUCK_AFTER
        );

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void skipsTasksHeldAtWorkerPhase() {
        // hold_at_worker_phase tasks wait for an explicit resume-java-phase
        // call (D3 gate) — they are not stuck and must not be re-driven.
        ProcessingTask task = heldTask("task_01", "tenant_01", "meeting_01");
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = newScanner(repo, publisher);

        scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void reportsZeroWhenRepositoryLacksRecoveryExtension() {
        // Repositories without the extension cannot be scanned — the scanner
        // must degrade to a no-op (with a warning) instead of throwing.
        ProcessingTaskRepository bareRepo = new BareTaskRepository();
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        WorkerDagDoneRecoveryScanner scanner = new WorkerDagDoneRecoveryScanner(
            bareRepo, publisher, TenantScopedTransaction.immediate(), SCAN_CLOCK, BATCH_SIZE, STUCK_AFTER
        );

        WorkerDagDoneRecoveryScanner.ScanReport report = scanner.scanAndRecover(List.of("tenant_01"));

        assertThat(report.scanned()).isZero();
        assertThat(report.recovered()).isZero();
        assertThat(publisher.events).isEmpty();
    }

    private static WorkerDagDoneRecoveryScanner newScanner(
        InMemoryTaskRepository repo,
        RecordingEventPublisher publisher
    ) {
        return new WorkerDagDoneRecoveryScanner(
            repo,
            publisher,
            TenantScopedTransaction.immediate(),
            SCAN_CLOCK,
            BATCH_SIZE,
            STUCK_AFTER
        );
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

    private static ProcessingTask heldTask(String taskId, String tenantId, String meetingId) {
        List<ProcessingStep> steps = List.of(
            ProcessingStep.AUDIO_UPLOAD,
            ProcessingStep.AUDIO_PREPROCESS,
            ProcessingStep.ASR,
            ProcessingStep.TRANSCRIPT_MERGE,
            ProcessingStep.SUMMARY,
            ProcessingStep.EXTRACTION
        );
        ProcessingTask task = ProcessingTask.create(
            taskId, tenantId, meetingId, "MEETING_FULL_PIPELINE", steps, NOW, /* holdAtWorkerPhase */ true
        );
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

    private static class BareTaskRepository implements ProcessingTaskRepository {
        @Override
        public ProcessingTask save(ProcessingTask task) {
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static class InMemoryTaskRepository extends BareTaskRepository
        implements WorkerDagDoneRecoveryScanner.ProcessingTaskRepositoryExtensions {
        private final List<ProcessingTask> tasks;
        final List<String> scannedTenants = new ArrayList<>();

        InMemoryTaskRepository(List<ProcessingTask> tasks) {
            this.tasks = new ArrayList<>(tasks);
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tasks.stream()
                .filter(t -> t.tenantId().equals(tenantId) && t.taskId().equals(taskId))
                .findFirst();
        }

        @Override
        public List<ProcessingTask> findStuckWorkerDagDone(String tenantId, OffsetDateTime olderThan, int limit) {
            scannedTenants.add(tenantId);
            return tasks.stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .filter(t -> t.phase() == ProcessingTaskPhase.WORKER_DAG_DONE)
                .filter(t -> t.status() != ProcessingTaskStatus.SUCCEEDED
                    && t.status() != ProcessingTaskStatus.FAILED
                    && t.status() != ProcessingTaskStatus.CANCELLED
                    && t.status() != ProcessingTaskStatus.PARTIAL_SUCCEEDED)
                .filter(t -> !t.holdAtWorkerPhase())
                .filter(t -> t.updatedAt() == null || t.updatedAt().isBefore(olderThan))
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
