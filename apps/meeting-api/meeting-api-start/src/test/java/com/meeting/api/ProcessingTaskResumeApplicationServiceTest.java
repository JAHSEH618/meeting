package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.task.ResumeJavaPhaseCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workstation D3 — resume Java phase after WORKER_DAG_DONE is reached and held.
 *
 * <p>Covers todo-final.md B5.3:
 * <ul>
 *   <li>idempotent on JAVA_LLM_RUNNING / TERMINAL</li>
 *   <li>throws INVALID_TASK_PHASE on bad phase</li>
 *   <li>normal happy-path promotes phase to JAVA_LLM_RUNNING</li>
 * </ul>
 */
class ProcessingTaskResumeApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T03:00:00Z");

    @Test
    void resumesHeldTaskFromWorkerDagDoneToJavaLlmRunning() {
        InMemoryTaskRepository tasks = workerDagDoneHeldTask();
        ProcessingTaskApplicationService service = service(tasks);

        var dto = service.resumeJavaPhase(new ResumeJavaPhaseCommand(
            "tenant_01", "task_01", "user_01", "idem_resume_1", "req_1", "trace_1"
        ));

        assertThat(dto.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
        assertThat(dto.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(tasks.findById("tenant_01", "task_01").orElseThrow().phase())
            .isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
    }

    @Test
    void resumeIsIdempotentWhenAlreadyInJavaLlmRunning() {
        InMemoryTaskRepository tasks = workerDagDoneHeldTask();
        // pre-advance the task by calling resume once
        ProcessingTaskApplicationService service = service(tasks);
        service.resumeJavaPhase(new ResumeJavaPhaseCommand(
            "tenant_01", "task_01", "user_01", "idem_resume_1", "req_1", "trace_1"
        ));

        var second = service.resumeJavaPhase(new ResumeJavaPhaseCommand(
            "tenant_01", "task_01", "user_01", "idem_resume_2", "req_2", "trace_2"
        ));

        assertThat(second.phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
    }

    @Test
    void resumeFromUnexpectedPhaseRaisesInvalidTaskPhase() {
        // task still at WORKER_DAG_RUNNING (worker callback hasn't completed yet)
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(workerRunningTask());
        ProcessingTaskApplicationService service = service(tasks);

        assertThatThrownBy(() -> service.resumeJavaPhase(new ResumeJavaPhaseCommand(
            "tenant_01", "task_01", "user_01", "idem_x", "req_x", "trace_x"
        )))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_TASK_PHASE);
    }

    @Test
    void resumeUnknownTaskRaisesTaskNotFound() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository(null);
        ProcessingTaskApplicationService service = service(tasks);

        assertThatThrownBy(() -> service.resumeJavaPhase(new ResumeJavaPhaseCommand(
            "tenant_01", "missing_task", "user_01", "idem_x", "req_x", "trace_x"
        )))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TASK_NOT_FOUND);
    }

    private static ProcessingTaskApplicationService service(InMemoryTaskRepository tasks) {
        return new ProcessingTaskApplicationService(
            tasks,
            new NoopMeetingRepository(),
            new NoopMessagePublisher(),
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static ProcessingTask workerRunningTask() {
        List<ProcessingStep> steps = List.of(
            ProcessingStep.AUDIO_UPLOAD,
            ProcessingStep.AUDIO_PREPROCESS,
            ProcessingStep.ASR,
            ProcessingStep.SUMMARY,
            ProcessingStep.EXTRACTION
        );
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE", steps, NOW, true);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimLease("worker_01", "worker_01:task_01:1", NOW.plusMinutes(5), NOW);
        return task;
    }

    private static InMemoryTaskRepository workerDagDoneHeldTask() {
        ProcessingTask task = workerRunningTask();
        task.completeWorkerPhase(
            ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR),
            List.<WorkerPhaseCompletedEvent.SkippedStep>of(),
            1, "worker_01:task_01:1", NOW.plusMinutes(1)
        );
        return new InMemoryTaskRepository(task);
    }

    private static final class InMemoryTaskRepository implements ProcessingTaskRepository {
        private ProcessingTask task;

        private InMemoryTaskRepository(ProcessingTask task) {
            this.task = task;
        }

        @Override
        public ProcessingTask save(ProcessingTask task) {
            this.task = task;
            return task;
        }

        @Override
        public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            return task != null && tenantId.equals(task.tenantId()) && taskId.equals(task.taskId())
                ? Optional.of(task) : Optional.empty();
        }

        @Override
        public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
            return task != null && tenantId.equals(task.tenantId()) && meetingId.equals(task.meetingId())
                ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<ExpiredLease> findExpiredLeases(String tenantId, OffsetDateTime now, int limit) {
            return List.of();
        }
    }

    private static final class NoopMessagePublisher implements MessagePublisher {
        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    private static final class NoopMeetingRepository implements MeetingRepository {
        @Override
        public com.meeting.api.domain.meeting.Meeting save(com.meeting.api.domain.meeting.Meeting meeting) {
            return meeting;
        }

        @Override
        public Optional<com.meeting.api.domain.meeting.Meeting> findById(String tenantId, String meetingId) {
            return Optional.empty();
        }

        @Override
        public List<com.meeting.api.domain.meeting.Meeting> findByTenantId(String tenantId) {
            return List.of();
        }
    }
}
