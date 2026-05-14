package com.meeting.api.adapter.internal;

import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.app.speaker.SpeakerCandidatesCallbackApplicationService;
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
import com.meeting.api.client.internal.callback.SpeakerCandidatesCallbackCommand;
import com.meeting.api.client.internal.callback.StepCallbackCommand;
import com.meeting.api.client.internal.callback.StepProgressHeartbeatCommand;
import com.meeting.api.client.internal.callback.TranscriptCallbackCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Collections;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final ProcessingTaskCallbackApplicationService callbackApplicationService;
    private final SpeakerCandidatesCallbackApplicationService speakerCallbackApplicationService;
    private final ObjectMapper objectMapper;
    private final MeetingApiMetrics metrics;

    public ProcessingTaskCallbackController(
        ProcessingTaskCallbackApplicationService callbackApplicationService,
        SpeakerCandidatesCallbackApplicationService speakerCallbackApplicationService,
        ObjectMapper objectMapper,
        MeetingApiMetrics metrics
    ) {
        this.callbackApplicationService = callbackApplicationService;
        this.speakerCallbackApplicationService = speakerCallbackApplicationService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PatchMapping("/steps/{stepName}")
    public ApiResponse<ProcessingTaskDTO> updateStep(
        @PathVariable String taskId,
        @PathVariable String stepName,
        @RequestBody String rawBody,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = parseBody(rawBody);
        CallbackMetadata metadata = metadata(request, rawBody);
        StepStatus status = StepStatus.valueOf(requiredString(payload, "status"));
        int progress = optionalInt(payload, "progress", 0);
        ProcessingStep step = ProcessingStep.valueOf(stepName);
        if (status == StepStatus.RUNNING && progress > 0) {
            metrics.callbackCounter("heartbeat", stepName).increment();
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
        metrics.callbackCounter("step_" + status.name().toLowerCase(), stepName).increment();
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
        @RequestBody String rawBody,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = parseBody(rawBody);
        CallbackMetadata metadata = metadata(request, rawBody);
        metrics.callbackCounter("complete", "WORKER_DAG").increment();
        return ApiResponse.ok(callbackApplicationService.completeWorkerPhase(new CompleteWorkerPhaseCommand(
            metadata,
            requiredString(payload, "tenantId"),
            optionalString(payload, "meetingId"),
            taskId,
            metadata.attemptNo(),
            requiredString(payload, "phase"),
            ProcessingTaskStatus.valueOf(requiredString(payload, "status")),
            parseSteps(payload.get("completedSteps")),
            parseSkippedSteps(payload.get("skippedSteps")),
            optionalString(payload, "artifactManifestId"),
            optionalDateTime(payload, "finishedAt", OffsetDateTime.now())
        )), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/fail")
    public ApiResponse<ProcessingTaskDTO> fail(
        @PathVariable String taskId,
        @RequestBody String rawBody,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = parseBody(rawBody);
        CallbackMetadata metadata = metadata(request, rawBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        ErrorCode code = ErrorCode.valueOf(String.valueOf(error.get("code")));
        metrics.callbackCounter("fail", optionalString(payload, "failedStep")).increment();
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
    public ApiResponse<Map<String, Object>> artifacts(@PathVariable String taskId, @RequestBody String rawBody, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, rawBody);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "ARTIFACTS"), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/transcript")
    public ApiResponse<ProcessingTaskDTO> transcript(@PathVariable String taskId, @RequestBody String rawBody, HttpServletRequest request) {
        Map<String, Object> payload = parseBody(rawBody);
        CallbackMetadata metadata = metadata(request, rawBody);
        metrics.callbackCounter("transcript", "TRANSCRIPT_MERGE").increment();
        return ApiResponse.ok(callbackApplicationService.writeTranscript(new TranscriptCallbackCommand(
            metadata,
            requiredString(payload, "tenantId"),
            requiredString(payload, "meetingId"),
            taskId,
            metadata.attemptNo(),
            optionalInt(payload, "transcriptVersion", 0),
            parseTranscriptSegments(payload.get("segments")),
            parseObject(payload.get("metadata")),
            optionalString(payload, "artifactManifestId")
        )), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/speaker-candidates")
    public ApiResponse<Map<String, Object>> speakerCandidates(@PathVariable String taskId, @RequestBody String rawBody, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, rawBody);
        Map<String, Object> payload = parseBody(rawBody);
        String tenantId = optionalString(payload, "tenantId");
        String meetingId = optionalString(payload, "meetingId");
        int attemptNo = optionalInt(payload, "attemptNo", metadata.attemptNo());
        speakerCallbackApplicationService.writeCandidates(new SpeakerCandidatesCallbackCommand(
            metadata,
            tenantId,
            meetingId,
            taskId,
            attemptNo,
            parseSpeakerCandidates(payload.get("speakerCandidates"))
        ));
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "SPEAKER_CANDIDATES"), metadata.requestId(), metadata.traceId());
    }

    @PostMapping("/embeddings")
    public ApiResponse<Map<String, Object>> embeddings(@PathVariable String taskId, @RequestBody String rawBody, HttpServletRequest request) {
        CallbackMetadata metadata = metadata(request, rawBody);
        return ApiResponse.ok(Map.of("accepted", true, "taskId", taskId, "callback", "EMBEDDINGS"), metadata.requestId(), metadata.traceId());
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid callback body", e);
        }
    }

    private static CallbackMetadata metadata(HttpServletRequest request, String rawBody) {
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
            sha256(rawBody)
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

    private static List<CompleteWorkerPhaseCommand.SkippedStep> parseSkippedSteps(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(value -> new CompleteWorkerPhaseCommand.SkippedStep(
                ProcessingStep.valueOf(String.valueOf(value.get("stepName"))),
                String.valueOf(value.get("reason"))
            ))
            .toList();
    }

    private static List<TranscriptCallbackCommand.Segment> parseTranscriptSegments(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty");
        }
        return values.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(value -> new TranscriptCallbackCommand.Segment(
                requiredString(value, "segmentId"),
                requiredLong(value, "startMs"),
                requiredLong(value, "endMs"),
                requiredString(value, "speakerLabel"),
                requiredString(value, "text"),
                optionalDecimal(value, "asrConfidence"),
                optionalDecimal(value, "diarizationConfidence"),
                optionalDecimal(value, "speakerConfidence"),
                optionalString(value, "timestampPrecision")
            ))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    private static List<SpeakerCandidatesCallbackCommand.SpeakerEntry> parseSpeakerCandidates(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        java.util.List<SpeakerCandidatesCallbackCommand.SpeakerEntry> result = new java.util.ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String speakerLabel = String.valueOf(map.get("speakerLabel"));
            java.util.List<SpeakerCandidatesCallbackCommand.Candidate> candidates = new java.util.ArrayList<>();
            Object candidatesRaw = map.get("candidates");
            if (candidatesRaw instanceof List<?> list) {
                for (Object c : list) {
                    if (!(c instanceof Map<?, ?> cm)) continue;
                    candidates.add(new SpeakerCandidatesCallbackCommand.Candidate(
                        cm.get("personId") == null ? null : String.valueOf(cm.get("personId")),
                        cm.get("speakerProfileId") == null ? null : String.valueOf(cm.get("speakerProfileId")),
                        cm.get("confidence") instanceof Number n ? n.doubleValue() : 0.0,
                        cm.get("matchStatus") == null ? null : String.valueOf(cm.get("matchStatus"))
                    ));
                }
            }
            SpeakerCandidatesCallbackCommand.PlainEmbedding embedding = null;
            Object embeddingRaw = map.get("embedding");
            if (embeddingRaw instanceof Map<?, ?> em) {
                Object valuesRaw = em.get("values");
                float[] floatValues = null;
                if (valuesRaw instanceof List<?> floats) {
                    floatValues = new float[floats.size()];
                    for (int i = 0; i < floats.size(); i++) {
                        Object v = floats.get(i);
                        floatValues[i] = v instanceof Number number ? number.floatValue() : 0f;
                    }
                }
                embedding = new SpeakerCandidatesCallbackCommand.PlainEmbedding(
                    em.get("format") == null ? null : String.valueOf(em.get("format")),
                    em.get("dimension") instanceof Number dn ? dn.intValue() : (floatValues == null ? 0 : floatValues.length),
                    floatValues,
                    em.get("checksum") == null ? null : String.valueOf(em.get("checksum")),
                    em.get("modelVersion") == null ? null : String.valueOf(em.get("modelVersion"))
                );
            }
            result.add(new SpeakerCandidatesCallbackCommand.SpeakerEntry(speakerLabel, candidates, embedding));
        }
        return result;
    }

    private static long requiredLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            return Long.parseLong(String.valueOf(value));
        }
        throw new IllegalArgumentException("missing required field: " + key);
    }

    private static BigDecimal optionalDecimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash callback body", e);
        }
    }
}
