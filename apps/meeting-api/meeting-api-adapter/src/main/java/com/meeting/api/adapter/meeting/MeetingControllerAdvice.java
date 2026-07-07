package com.meeting.api.adapter.meeting;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import com.meeting.api.domain.llm.LlmProviderException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to stable error responses.
 * Spec §7 exception mapping table. Add entries as new exception types are introduced.
 */
@RestControllerAdvice
public class MeetingControllerAdvice {
    private static final Logger log = LoggerFactory.getLogger(MeetingControllerAdvice.class);

    @ExceptionHandler(TenantContextMissingException.class)
    public ResponseEntity<ApiResponse<Void>> handleTenantContextMissing(TenantContextMissingException ex) {
        return error(HttpStatus.FORBIDDEN, ex.errorCode(), ex.getMessage(), false, Map.of());
    }

    @ExceptionHandler(LlmProviderException.class)
    public ResponseEntity<ApiResponse<Void>> handleLlmProvider(LlmProviderException ex) {
        ErrorCode code = ex.errorCode();
        boolean retryable = code == ErrorCode.LLM_PROVIDER_TIMEOUT
            || code == ErrorCode.LLM_RATE_LIMIT
            || code == ErrorCode.LLM_OUTPUT_TRUNCATED;
        HttpStatus status = code == ErrorCode.LLM_RATE_LIMIT ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return error(status, code, ex.getMessage(), retryable, Map.of());
    }

    @ExceptionHandler(MinutesApplicationService.VersionConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleMinutesVersionConflict(MinutesApplicationService.VersionConflictException ex) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, ex.getMessage(), false, Map.of());
    }

    @ExceptionHandler(TranscriptApplicationService.TranscriptVersionConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleTranscriptVersionConflict(TranscriptApplicationService.TranscriptVersionConflictException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expectedVersion", ex.expectedVersion());
        details.put("actualVersion", ex.actualVersion());
        // Transcript-version conflicts are transient/recoverable: a bounded retry with the
        // correct next version can succeed, so signal retryable=true to the worker.
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, ex.getMessage(), true, details);
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResponse<Void>> handleApplication(ApplicationException ex) {
        return error(HttpStatus.valueOf(ex.httpStatus()), ex.errorCode(), ex.getMessage(), ex.retryable(), Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(IllegalArgumentException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED, ex.getMessage(), false, Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, ex.getMessage(), false, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "服务内部错误", false, Map.of());
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ErrorCode code, String message, boolean retryable, Map<String, Object> details) {
        ApiResponse<Void> body = new ApiResponse<>(
            false,
            null,
            new ErrorInfo(code, message, retryable, details),
            null,
            null
        );
        return ResponseEntity.status(status).body(body);
    }
}
