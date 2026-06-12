package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskLeaseScanner;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.ProcessingTaskStep;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskLeaseScannerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-13T02:30:00Z");

    @Test
    void marksRunningTasksWithExpiredLeaseAsOrphanedAndLeavesFreshOnesAlone() {
        ProcessingTask expired = runningTaskWithLease("task_expired", NOW.minusMinutes(2));
        ProcessingTask fresh = runningTaskWithLease("task_fresh", NOW.plusMinutes(2));
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(List.of(expired, fresh));

        ProcessingTaskLeaseScanner scanner = new ProcessingTaskLeaseScanner(
            tasks,
            event -> {},
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            10
        );

        ProcessingTaskLeaseScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01"));

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.orphaned()).isEqualTo(1);
        assertThat(report.requeued()).isEqualTo(1);
        assertThat(tasks.byId("task_expired").status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        assertThat(tasks.byId("task_expired").attemptNo()).isEqualTo(2);
        assertThat(tasks.byId("task_fresh").status()).isEqualTo(ProcessingTaskStatus.RUNNING);
    }

    @Test
    void doesNotTouchTerminalTasksEvenIfRepositoryReturnsThem() {
        ProcessingTask succeeded = succeededTask("task_done");
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(List.of(succeeded)) {
            @Override
            public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
                return List.of(new ExpiredLease(succeeded.tenantId(), succeeded.taskId()));
            }
        };

        ProcessingTaskLeaseScanner scanner = new ProcessingTaskLeaseScanner(
            tasks,
            event -> {},
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            10
        );

        ProcessingTaskLeaseScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01"));

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.orphaned()).isZero();
        assertThat(tasks.byId("task_done").status()).isEqualTo(ProcessingTaskStatus.SUCCEEDED);
    }

    @Test
    void reportsZeroWhenRepositoryReturnsNoCandidates() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(List.of());

        ProcessingTaskLeaseScanner scanner = new ProcessingTaskLeaseScanner(
            tasks,
            event -> {},
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            10
        );

        ProcessingTaskLeaseScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01"));

        assertThat(report.scanned()).isZero();
        assertThat(report.orphaned()).isZero();
    }

    private static ProcessingTask runningTaskWithLease(String taskId, OffsetDateTime leaseExpiresAt) {
        ProcessingTaskStep step = ProcessingTaskStep.pending(
            ProcessingStep.ASR,
            com.meeting.api.client.enums.ProcessingStepUpdateSource.AI_WORKER_CALLBACK
        );
        return ProcessingTask.restore(
            taskId,
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            ProcessingTaskStatus.RUNNING,
            ProcessingTaskPhase.WORKER_DAG_RUNNING,
            1,
            "ASR",
            null,
            false,
            "worker_dev_001:" + taskId + ":1",
            leaseExpiresAt,
            NOW.minusMinutes(1),
            NOW.minusMinutes(5),
            NOW.minusMinutes(1),
            List.of(step)
        );
    }

    private static ProcessingTask succeededTask(String taskId) {
        ProcessingTaskStep step = ProcessingTaskStep.pending(
            ProcessingStep.ASR,
            com.meeting.api.client.enums.ProcessingStepUpdateSource.AI_WORKER_CALLBACK
        );
        return ProcessingTask.restore(
            taskId,
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            ProcessingTaskStatus.SUCCEEDED,
            ProcessingTaskPhase.TERMINAL,
            1,
            null,
            null,
            false,
            null,
            null,
            null,
            NOW.minusMinutes(10),
            NOW.minusMinutes(1),
            List.of(step)
        );
    }

    private static class InMemoryTaskRepository implements ProcessingTaskRepository {
        private final List<ProcessingTask> tasks;

        InMemoryTaskRepository(List<ProcessingTask> initial) {
            this.tasks = new ArrayList<>(initial);
        }

        ProcessingTask byId(String taskId) {
            return tasks.stream().filter(task -> task.taskId().equals(taskId)).findFirst().orElseThrow();
        }

        @Override
        public ProcessingTask save(ProcessingTask task) {
            for (int index = 0; index < tasks.size(); index += 1) {
                if (tasks.get(index).taskId().equals(task.taskId())) {
                    tasks.set(index, task);
                    return task;
                }
            }
            tasks.add(task);
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return tasks.stream()
                .filter(task -> task.tenantId().equals(tenantId) && task.taskId().equals(taskId))
                .findFirst();
        }

        @Override
        public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
            return findById(tenantId, taskId);
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return tasks.stream()
                .filter(task -> task.tenantId().equals(tenantId) && meetingId.equals(task.meetingId()))
                .findFirst();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return tasks.stream()
                .filter(task -> task.tenantId().equals(tenantId))
                .filter(task -> task.status() == ProcessingTaskStatus.RUNNING)
                .filter(task -> task.phase() == ProcessingTaskPhase.WORKER_DAG_RUNNING)
                .filter(task -> task.leaseExpiresAt() != null && task.leaseExpiresAt().isBefore(now))
                .limit(limit)
                .map(task -> new ExpiredLease(task.tenantId(), task.taskId()))
                .toList();
        }
    }
}
