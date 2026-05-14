package com.meeting.api.adapter.meeting;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
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
        return error(HttpStatus.FORBIDDEN, ex.errorCode(), ex.getMessage(), false);
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResponse<Void>> handleApplication(ApplicationException ex) {
        return error(HttpStatus.valueOf(ex.httpStatus()), ex.errorCode(), ex.getMessage(), ex.retryable());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(IllegalArgumentException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED, ex.getMessage(), false);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, ex.getMessage(), false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "服务内部错误", false);
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ErrorCode code, String message, boolean retryable) {
        ApiResponse<Void> body = new ApiResponse<>(
            false,
            null,
            new ErrorInfo(code, message, retryable, java.util.Map.of()),
            null,
            null
        );
        return ResponseEntity.status(status).body(body);
    }
}
