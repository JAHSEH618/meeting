package com.meeting.api.infrastructure.persistence.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.ProcessingTaskStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessingTaskRepository implements ProcessingTaskRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessingTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ProcessingTask save(ProcessingTask task) {
        jdbcTemplate.update(
            """
            INSERT INTO processing_tasks (
              id, tenant_id, meeting_id, task_type, status, phase, progress,
              current_step, attempt_count, lease_owner, lease_expires_at,
              heartbeat_at, last_error_code, trace_id, created_at
            )
            VALUES (?, ?, ?, ?, ?::task_status, ?::task_phase, ?, ?::processing_step,
                    ?, ?, ?, ?, ?, NULL, ?)
            ON CONFLICT (id) DO UPDATE SET
              status = EXCLUDED.status,
              phase = EXCLUDED.phase,
              progress = EXCLUDED.progress,
              current_step = EXCLUDED.current_step,
              attempt_count = EXCLUDED.attempt_count,
              lease_owner = EXCLUDED.lease_owner,
              lease_expires_at = EXCLUDED.lease_expires_at,
              heartbeat_at = EXCLUDED.heartbeat_at,
              last_error_code = EXCLUDED.last_error_code
            """,
            task.taskId(),
            task.tenantId(),
            task.meetingId(),
            task.taskType(),
            task.status().name(),
            task.phase().name(),
            aggregateProgress(task),
            task.currentStep(),
            task.attemptNo(),
            task.leaseOwner(),
            toTimestamp(task.leaseExpiresAt()),
            toTimestamp(task.heartbeatAt()),
            task.lastErrorCode(),
            toTimestamp(task.createdAt())
        );
        for (ProcessingTaskStep step : task.steps()) {
            saveStep(task, step);
        }
        return task;
    }

    @Override
    public Optional<ProcessingTask> findById(String tenantId, String taskId) {
        List<ProcessingTask> tasks = jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, task_type, status, phase, current_step,
                   attempt_count, lease_owner, lease_expires_at, heartbeat_at,
                   last_error_code, created_at, updated_at
              FROM processing_tasks
             WHERE tenant_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapTask(rs, findSteps(tenantId, rs.getString("id"))),
            tenantId,
            taskId
        );
        return tasks.stream().findFirst();
    }

    @Override
    public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
        List<ProcessingTask> tasks = jdbcTemplate.query(
            """
            SELECT id, tenant_id, meeting_id, task_type, status, phase, current_step,
                   attempt_count, lease_owner, lease_expires_at, heartbeat_at,
                   last_error_code, created_at, updated_at
              FROM processing_tasks
             WHERE tenant_id = ? AND meeting_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """,
            (rs, rowNum) -> mapTask(rs, findSteps(tenantId, rs.getString("id"))),
            tenantId,
            meetingId
        );
        return tasks.stream().findFirst();
    }

    private void saveStep(ProcessingTask task, ProcessingTaskStep step) {
        jdbcTemplate.update(
            """
            INSERT INTO processing_task_steps (
              id, tenant_id, task_id, step_name, status, progress, attempt_count,
              lease_owner, heartbeat_at, error_code, started_at, finished_at
            )
            VALUES (?, ?, ?, ?::processing_step, ?::step_status, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (task_id, step_name, attempt_count) DO UPDATE SET
              status = EXCLUDED.status,
              progress = EXCLUDED.progress,
              lease_owner = EXCLUDED.lease_owner,
              heartbeat_at = EXCLUDED.heartbeat_at,
              error_code = EXCLUDED.error_code,
              started_at = EXCLUDED.started_at,
              finished_at = EXCLUDED.finished_at
            """,
            "pts_" + UUID.randomUUID().toString().replace("-", ""),
            task.tenantId(),
            task.taskId(),
            step.stepName().name(),
            step.status().name(),
            step.progress(),
            step.attemptNo() == null ? 0 : step.attemptNo(),
            step.leaseOwner(),
            toTimestamp(step.heartbeatAt()),
            step.errorCode(),
            toTimestamp(step.startedAt()),
            toTimestamp(step.finishedAt())
        );
    }

    private List<ProcessingTaskStep> findSteps(String tenantId, String taskId) {
        return jdbcTemplate.query(
            """
            SELECT step_name, status, progress, attempt_count, lease_owner,
                   heartbeat_at, error_code, started_at, finished_at
              FROM processing_task_steps
             WHERE tenant_id = ? AND task_id = ?
             ORDER BY created_at ASC, step_name ASC
            """,
            this::mapStep,
            tenantId,
            taskId
        );
    }

    private ProcessingTask mapTask(ResultSet rs, List<ProcessingTaskStep> steps) throws SQLException {
        return ProcessingTask.restore(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("meeting_id"),
            rs.getString("task_type"),
            ProcessingTaskStatus.valueOf(rs.getString("status")),
            ProcessingTaskPhase.valueOf(rs.getString("phase")),
            rs.getInt("attempt_count"),
            rs.getString("current_step"),
            rs.getString("last_error_code"),
            rs.getString("last_error_code") != null,
            rs.getString("lease_owner"),
            toOffsetDateTime(rs.getTimestamp("lease_expires_at")),
            toOffsetDateTime(rs.getTimestamp("heartbeat_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at")),
            steps
        );
    }

    private ProcessingTaskStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        ProcessingStep step = ProcessingStep.valueOf(rs.getString("step_name"));
        return ProcessingTaskStep.restore(
            step,
            StepStatus.valueOf(rs.getString("status")),
            rs.getInt("progress"),
            toOffsetDateTime(rs.getTimestamp("started_at")),
            toOffsetDateTime(rs.getTimestamp("finished_at")),
            toOffsetDateTime(rs.getTimestamp("heartbeat_at")),
            rs.getInt("attempt_count") == 0 ? null : rs.getInt("attempt_count"),
            rs.getString("lease_owner"),
            null,
            null,
            rs.getString("error_code"),
            defaultSourceFor(step)
        );
    }

    private static ProcessingStepUpdateSource defaultSourceFor(ProcessingStep step) {
        return switch (step) {
            case AUDIO_UPLOAD, SUMMARY, EXTRACTION, EXPORT -> ProcessingStepUpdateSource.JAVA_TASK_SERVICE;
            default -> ProcessingStepUpdateSource.AI_WORKER_CALLBACK;
        };
    }

    private static int aggregateProgress(ProcessingTask task) {
        List<ProcessingTaskStep> steps = task.steps();
        if (steps.isEmpty()) {
            return 0;
        }
        return (int) Math.round(steps.stream().mapToInt(ProcessingTaskStep::progress).average().orElse(0));
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
