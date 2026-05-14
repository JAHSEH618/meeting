package com.meeting.api.adapter.storage;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.storage.AbortAudioUploadCommand;
import com.meeting.api.client.storage.AudioUploadFacade;
import com.meeting.api.client.storage.AudioUploadPartUploadDTO;
import com.meeting.api.client.storage.AudioUploadSessionDTO;
import com.meeting.api.client.storage.CompleteAudioUploadCommand;
import com.meeting.api.client.storage.CreateAudioUploadPartCommand;
import com.meeting.api.client.storage.CreateAudioUploadSessionCommand;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings/{meetingId}/files/audio/uploads")
public class AudioUploadController {
    private final AudioUploadFacade audioUploadFacade;

    public AudioUploadController(AudioUploadFacade audioUploadFacade) {
        this.audioUploadFacade = audioUploadFacade;
    }

    @PostMapping
    public ApiResponse<AudioUploadSessionDTO> create(
        @PathVariable String meetingId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateAudioUploadRequest request
    ) {
        AudioUploadSessionDTO session = audioUploadFacade.createSession(new CreateAudioUploadSessionCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            request.fileName(),
            request.contentType(),
            request.fileSizeBytes(),
            request.fileSha256(),
            request.partSizeBytes(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(session, requestId, traceId);
    }

    @PostMapping("/{uploadId}/parts")
    public ApiResponse<AudioUploadPartUploadDTO> createPart(
        @PathVariable String meetingId,
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateAudioUploadPartRequest request
    ) {
        AudioUploadPartUploadDTO part = audioUploadFacade.createPart(new CreateAudioUploadPartCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            uploadId,
            request.partNumber(),
            request.sizeBytes(),
            request.partSha256(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(part, requestId, traceId);
    }

    @PostMapping("/{uploadId}/complete")
    public ApiResponse<AudioUploadSessionDTO> complete(
        @PathVariable String meetingId,
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CompleteAudioUploadRequest request
    ) {
        AudioUploadSessionDTO session = audioUploadFacade.complete(new CompleteAudioUploadCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            uploadId,
            request.fileSha256(),
            request.durationMs(),
            request.parts() == null ? List.of() : request.parts().stream()
                .map(part -> new CompleteAudioUploadCommand.PartCommand(part.partNumber(), part.partSha256(), part.etag()))
                .toList(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(session, requestId, traceId);
    }

    @PostMapping("/{uploadId}/abort")
    public ApiResponse<Void> abort(
        @PathVariable String meetingId,
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        audioUploadFacade.abort(new AbortAudioUploadCommand(
            TenantContextHolder.currentTenantId(),
            meetingId,
            uploadId,
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(null, requestId, traceId);
    }

    @GetMapping("/{uploadId}")
    public ResponseEntity<ApiResponse<AudioUploadSessionDTO>> get(
        @PathVariable String meetingId,
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return audioUploadFacade.get(TenantContextHolder.currentTenantId(), meetingId, uploadId)
            .map(session -> ResponseEntity.ok(ApiResponse.ok(session, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateAudioUploadRequest(
        String fileName,
        String contentType,
        long fileSizeBytes,
        String fileSha256,
        Integer partSizeBytes
    ) {
    }

    public record CreateAudioUploadPartRequest(
        int partNumber,
        long sizeBytes,
        String partSha256
    ) {
    }

    public record CompleteAudioUploadRequest(
        String fileSha256,
        Long durationMs,
        List<CompleteAudioUploadPartRequest> parts
    ) {
    }

    public record CompleteAudioUploadPartRequest(
        int partNumber,
        String partSha256,
        String etag
    ) {
    }
}
