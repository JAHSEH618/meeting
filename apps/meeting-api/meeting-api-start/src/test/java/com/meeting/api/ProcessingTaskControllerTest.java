package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.task.ProcessingTaskController;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.task.CancelTaskCommand;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.client.task.ProcessingTaskFacade;
import com.meeting.api.client.task.RetryTaskCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskControllerTest {

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createBuildsCommandFromTenantContextAndPath() {
        CapturingProcessingTaskFacade facade = new CapturingProcessingTaskFacade();
        ProcessingTaskController controller = new ProcessingTaskController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.create(
            "meeting_01",
            "req_01",
            "trace_01",
            "idem_01",
            new ProcessingTaskController.CreateTaskRequest("MEETING_FULL_PIPELINE", Map.of("enableAsr", true), Map.of("chunkStrategyVersion", "v1"))
        );

        assertThat(response.success()).isTrue();
        assertThat(facade.lastCreate.tenantId()).isEqualTo("tenant_01");
        assertThat(facade.lastCreate.meetingId()).isEqualTo("meeting_01");
        assertThat(facade.lastCreate.requestedBy()).isEqualTo("user_01");
        assertThat(facade.lastCreate.idempotencyKey()).isEqualTo("idem_01");
    }

    @Test
    void latestTaskUsesTenantContextAndMeetingPath() {
        CapturingProcessingTaskFacade facade = new CapturingProcessingTaskFacade();
        ProcessingTaskController controller = new ProcessingTaskController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.getLatestForMeeting("meeting_01", "req_02", "trace_02");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(facade.lastLatestTenantId).isEqualTo("tenant_01");
        assertThat(facade.lastLatestMeetingId).isEqualTo("meeting_01");
    }

    private static final class CapturingProcessingTaskFacade implements ProcessingTaskFacade {
        private final ProcessingTaskDTO task = new ProcessingTaskDTO(
            "task_01",
            "tenant_01",
            "meeting_01",
            "MEETING_FULL_PIPELINE",
            ProcessingTaskStatus.QUEUED,
            ProcessingTaskPhase.WORKER_DAG_RUNNING,
            1,
            null,
            null,
            false,
            null,
            null,
            OffsetDateTime.parse("2026-05-13T02:00:00Z"),
            OffsetDateTime.parse("2026-05-13T02:00:00Z"),
            List.of()
        );
        private CreateProcessingTaskCommand lastCreate;
        private String lastLatestTenantId;
        private String lastLatestMeetingId;

        @Override
        public ProcessingTaskDTO create(CreateProcessingTaskCommand command) {
            lastCreate = command;
            return task;
        }

        @Override
        public Optional<ProcessingTaskDTO> get(String tenantId, String taskId) {
            return Optional.of(task);
        }

        @Override
        public Optional<ProcessingTaskDTO> getLatestForMeeting(String tenantId, String meetingId) {
            lastLatestTenantId = tenantId;
            lastLatestMeetingId = meetingId;
            return Optional.of(task);
        }

        @Override
        public ProcessingTaskDTO retry(RetryTaskCommand command) {
            return task;
        }

        @Override
        public ProcessingTaskDTO cancel(CancelTaskCommand command) {
            return task;
        }
    }
}
