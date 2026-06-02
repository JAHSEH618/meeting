package com.meeting.api.adapter.speaker;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.client.speaker.SpeakerEnrollmentDTO;
import com.meeting.api.client.speaker.SpeakerProfileDTO;
import com.meeting.api.client.speaker.SpeakerProfileFacade;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SpeakerProfileController {
    private final SpeakerProfileFacade facade;

    public SpeakerProfileController(SpeakerProfileFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/speaker-profiles")
    public ResponseEntity<ApiResponse<List<SpeakerProfileDTO>>> list(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.list(TenantContextHolder.currentTenantId());
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @GetMapping("/api/speaker-profiles/{profileId}")
    public ResponseEntity<ApiResponse<SpeakerProfileDTO>> get(
        @PathVariable String profileId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return facade.get(TenantContextHolder.currentTenantId(), profileId)
            .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/speaker-profiles")
    public ResponseEntity<ApiResponse<SpeakerProfileDTO>> create(
        @RequestBody CreateProfileRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        SpeakerProfileDTO result = facade.create(new CreateSpeakerProfileCommand(
            TenantContextHolder.currentTenantId(),
            body.personId(),
            body.displayName(),
            body.resolvedConsentSource(),
            body.resolvedConsentVersion(),
            userId,
            requestId,
            traceId,
            idempotencyKey
        ));
        return ResponseEntity.ok(ApiResponse.ok(result, requestId, traceId));
    }

    @PostMapping("/api/speaker-profiles/{profileId}/revoke")
    public ResponseEntity<ApiResponse<Void>> revoke(
        @PathVariable String profileId,
        @RequestBody(required = false) RevokeRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        facade.revoke(TenantContextHolder.currentTenantId(), profileId, userId, body == null ? null : body.reason());
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @DeleteMapping("/api/speaker-profiles/{profileId}")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable String profileId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        facade.delete(TenantContextHolder.currentTenantId(), profileId, userId, "user_request");
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @PostMapping("/api/speaker-profiles/{profileId}/enrollments")
    public ResponseEntity<ApiResponse<SpeakerEnrollmentDTO>> addEnrollment(
        @PathVariable String profileId,
        @RequestBody CreateEnrollmentRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        SpeakerEnrollmentDTO result = facade.addEnrollment(new CreateSpeakerEnrollmentCommand(
            TenantContextHolder.currentTenantId(),
            profileId,
            body.resolvedAudioFileId(),
            userId,
            requestId,
            traceId,
            idempotencyKey
        ));
        return ResponseEntity.ok(ApiResponse.ok(result, requestId, traceId));
    }

    @GetMapping("/api/speaker-profiles/{profileId}/enrollments")
    public ResponseEntity<ApiResponse<List<SpeakerEnrollmentDTO>>> listEnrollments(
        @PathVariable String profileId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.listEnrollments(TenantContextHolder.currentTenantId(), profileId);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    public record CreateProfileRequest(
        String personId,
        String displayName,
        String consentReference,
        String consentSource,
        String consentVersion
    ) {
        String resolvedConsentSource() {
            if (consentReference == null || consentReference.isBlank()) {
                return consentSource;
            }
            int separator = consentReference.indexOf(':');
            return separator < 0 ? consentReference : consentReference.substring(0, separator);
        }

        String resolvedConsentVersion() {
            if (consentReference == null || consentReference.isBlank()) {
                return consentVersion;
            }
            int separator = consentReference.indexOf(':');
            return separator < 0 || separator == consentReference.length() - 1
                ? null
                : consentReference.substring(separator + 1);
        }
    }

    public record RevokeRequest(String reason) {
    }

    public record CreateEnrollmentRequest(String audioFileId, String sourceAudioFileId, String consentReference, String language) {
        String resolvedAudioFileId() {
            return audioFileId == null || audioFileId.isBlank() ? sourceAudioFileId : audioFileId;
        }
    }
}
