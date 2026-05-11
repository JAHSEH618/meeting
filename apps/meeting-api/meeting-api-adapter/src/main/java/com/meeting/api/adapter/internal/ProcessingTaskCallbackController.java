package com.meeting.api.adapter.internal;

import com.meeting.api.client.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/processing-tasks/{taskId}")
public class ProcessingTaskCallbackController {
    @PatchMapping("/steps/{stepName}")
    public ApiResponse<Map<String, Object>> updateStep(
        @PathVariable String taskId,
        @PathVariable String stepName,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, stepName, requestId, traceId);
    }

    @PostMapping("/artifacts")
    public ApiResponse<Map<String, Object>> artifacts(
        @PathVariable String taskId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, "ARTIFACTS", requestId, traceId);
    }

    @PostMapping("/transcript")
    public ApiResponse<Map<String, Object>> transcript(
        @PathVariable String taskId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, "TRANSCRIPT", requestId, traceId);
    }

    @PostMapping("/speaker-candidates")
    public ApiResponse<Map<String, Object>> speakerCandidates(
        @PathVariable String taskId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, "SPEAKER_CANDIDATES", requestId, traceId);
    }

    @PostMapping("/complete")
    public ApiResponse<Map<String, Object>> complete(
        @PathVariable String taskId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, "COMPLETE", requestId, traceId);
    }

    @PostMapping("/fail")
    public ApiResponse<Map<String, Object>> fail(
        @PathVariable String taskId,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody Map<String, Object> payload
    ) {
        return accepted(taskId, "FAIL", requestId, traceId);
    }

    private ApiResponse<Map<String, Object>> accepted(String taskId, String stepName, String requestId, String traceId) {
        return ApiResponse.ok(
            Map.of("accepted", true, "taskId", taskId, "stepName", stepName),
            requestId,
            traceId
        );
    }
}
