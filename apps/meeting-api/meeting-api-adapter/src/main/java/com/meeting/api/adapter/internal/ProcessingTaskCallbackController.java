package com.meeting.api.adapter.internal;

import com.meeting.api.app.task.ProcessingTaskCallbackApplicationService;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.client.internal.callback.CompleteWorkerPhaseCommand;
import com.meeting.api.client.internal.callback.FailTaskCommand;
import com.meeting.api.client.internal.callback.StepCallbackCommand;
import com.meeting.api.client.internal.callback.StepProgressHeartbeatCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/processing-tasks/{taskId}")
public class ProcessingTaskCallbackController {
    private final ProcessingTaskCallbackApplicationService callbackApplicationService;

    public ProcessingTaskCallbackController(ProcessingTaskCallbackApplicationService callbackApplicationService) {
        this.callbackApplicationService = callbackApplicationService;
    }

    @PatchMapping("/steps/{stepName}")
    public ApiResponse<ProcessingTaskDTO> updateStep(
        @PathVariable String taskId,
        @PathVariable String stepName,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        CallbackMetadata metadata = metadata(request, payload);
        StepStatus status = StepStatus.valueOf(requiredString(payload, "status"));
        int progress = optionalInt(payload, "progress", 0);
        ProcessingStep step = ProcessingStep.valueOf(stepName);
        if (status == StepStatus.RUNNING && progress > 0) {
            return ApiResponse.ok(callbackApplicationService.heartbeat(new StepProgressHeartbeatCommand(
                metadata,
                requiredString(payload, "tenantId"),
                optionalString(payload, "meetingId"),
                taskId,
                metadata.attemptNo(),
                step,
                progress,
                optionalDateTime(payload, "heartbeatAt", OffsetDateTime.now())
            )), metadata.requestId(), metadata.traceId());
        }
        return ApiResponse.ok(callbackApplicationService.updateStep(new StepCallbackCommand(
            metadata,
            requiredString(payload, "tenantId"),
            optionalString(payload, "meetingId"),
            taskId,
            metadata.attemptNo(),
            step,
            status,
            progress,
            optionalString(payload, "errorCode"),
            optionalString(payload, "artifactManifestId")
        )), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/complete")
    public ApiResponse<ProcessingTaskDTO> completeWorkerPhase(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        CallbackMetadata metadata = metadata(request, payload);
        return ApiResponse.ok(callbackApplicationService.completeWorkerPhase(new CompleteWorkerPhaseCommand(
            metadata,
            requiredString(payload, "tenantId"),
            optionalString(payload, "meetingId"),
            taskId,
            metadata.attemptNo(),
            requiredString(payload, "phase"),
            ProcessingTaskStatus.valueOf(requiredString(payload, "status")),
            parseSteps(payload.get("completedSteps")),
            List.of(),
            optionalString(payload, "artifactManifestId"),
            optionalDateTime(payload, "finishedAt", OffsetDateTime.now())
        )), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/fail")
    public ApiResponse<ProcessingTaskDTO> fail(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        CallbackMetadata metadata = metadata(request, payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        ErrorCode code = ErrorCode.valueOf(String.valueOf(error.get("code")));
        return ApiResponse.ok(callbackApplicationService.fail(new FailTaskCommand(
            metadata,
            requiredString(payload, "tenantId"),
            optionalString(payload, "meetingId"),
            taskId,
            metadata.attemptNo(),
            ProcessingStep.valueOf(requiredString(payload, "failedStep")),
            ErrorInfo.of(code, String.valueOf(error.get("message")), Boolean.TRUE.equals(error.get("retryable"))),
            optionalString(payload, "artifactManifestId"),
            optionalDateTime(payload, "failedAt", OffsetDateTime.now())
        )), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/artifacts")
    public ApiResponse<Map<String, Object>> artifacts(@PathVariable String taskId, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, payload);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "ARTIFACTS"), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/transcript")
    public ApiResponse<Map<String, Object>> transcript(@PathVariable String taskId, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, payload);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "TRANSCRIPT"), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/speaker-candidates")
    public ApiResponse<Map<String, Object>> speakerCandidates(@PathVariable String taskId, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, payload);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "SPEAKER_CANDIDATES"), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/embeddings")
    public ApiResponse<Map<String, Object>> embeddings(@PathVariable String taskId, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, payload);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "EMBEDDINGS"), metadata.requestId(), metadata.traceId());
    }

    private static CallbackMetadata metadata(HttpServletRequest request, Map<String, Object> payload) {
        return new CallbackMetadata(
            requiredHeader(request, "X-Worker-Id"),
            Integer.parseInt(requiredHeader(request, "X-Attempt-No")),
            requiredHeader(request, "X-Lease-Owner"),
            request.getMethod(),
            requiredHeader(request, "X-Request-Id"),
            requiredHeader(request, "X-Trace-Id"),
            OffsetDateTime.parse(requiredHeader(request, "X-Timestamp")),
            requiredHeader(request, "X-Nonce"),
            requiredHeader(request, "Idempotency-Key"),
            requiredHeader(request, "X-Signature"),
            requestUriWithQuery(request),
            sha256(payload.toString())
        );
    }

    private static String requestUriWithQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isBlank() ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required header: " + name);
        }
        return value;
    }

    private static String requiredString(Map<String, Object> payload, String key) {
        String value = optionalString(payload, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private static String optionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int optionalInt(Map<String, Object> payload, String key, int defaultValue) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static OffsetDateTime optionalDateTime(Map<String, Object> payload, String key, OffsetDateTime defaultValue) {
        String value = optionalString(payload, key);
        return value == null || value.isBlank() ? defaultValue : OffsetDateTime.parse(value);
    }

    private static List<ProcessingStep> parseSteps(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(value -> ProcessingStep.valueOf(String.valueOf(value))).toList();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash callback body", e);
        }
    }
}
