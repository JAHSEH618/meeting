package com.meeting.api.adapter.task;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.task.CancelTaskCommand;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.client.task.ProcessingTaskFacade;
import com.meeting.api.client.task.RetryTaskCommand;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProcessingTaskController {
    private final ProcessingTaskFacade processingTaskFacade;

    public ProcessingTaskController(ProcessingTaskFacade processingTaskFacade) {
        this.processingTaskFacade = processingTaskFacade;
    }

    @PostMapping("/api/meetings/{meetingId}/processing-tasks")
    public ApiResponse<ProcessingTaskDTO> create(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateTaskRequest request
    ) {
        ProcessingTaskDTO task = processingTaskFacade.create(new CreateProcessingTaskCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            request.taskType(),
            request.options(),
            request.expectedInputVersion(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(task, requestId, traceId);
    }

    @GetMapping("/api/processing-tasks/{taskId}")
    public ResponseEntity<ApiResponse<ProcessingTaskDTO>> get(
        @PathVariable String taskId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return processingTaskFacade.get(TenantContextHolder.currentTenantId(), taskId)
            .map(task -> ResponseEntity.ok(ApiResponse.ok(task, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/processing-tasks/{taskId}/retry")
    public ApiResponse<ProcessingTaskDTO> retry(
        @PathVariable String taskId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody(required = false) RetryRequest request
    ) {
        ProcessingTaskDTO task = processingTaskFacade.retry(new RetryTaskCommand(
            TenantContextHolder.currentTenantId(),
            taskId,
            TenantContextHolder.currentUserId(),
            request == null ? null : request.reason(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(task, requestId, traceId);
    }

    @PostMapping("/api/processing-tasks/{taskId}/cancel")
    public ApiResponse<ProcessingTaskDTO> cancel(
        @PathVariable String taskId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestBody(required = false) CancelRequest request
    ) {
        ProcessingTaskDTO task = processingTaskFacade.cancel(new CancelTaskCommand(
            TenantContextHolder.currentTenantId(),
            taskId,
            TenantContextHolder.currentUserId(),
            request == null ? null : request.reason(),
            requestId,
            traceId
        ));
        return ApiResponse.ok(task, requestId, traceId);
    }

    public record CreateTaskRequest(String taskType, Map<String, Object> options, Map<String, Object> expectedInputVersion) {
    }

    public record RetryRequest(String reason) {
    }

    public record CancelRequest(String reason) {
    }
}
