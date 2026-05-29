package com.meeting.api.adapter.storage;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.storage.AbortGenericFileUploadCommand;
import com.meeting.api.client.storage.CompleteGenericFileUploadCommand;
import com.meeting.api.client.storage.CreateGenericFilePartCommand;
import com.meeting.api.client.storage.CreateGenericFileUploadCommand;
import com.meeting.api.client.storage.GenericFileCompleteDTO;
import com.meeting.api.client.storage.GenericFileFacade;
import com.meeting.api.client.storage.GenericFileUploadPartDTO;
import com.meeting.api.client.storage.GenericFileUploadSessionDTO;
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
@RequestMapping("/api/files")
public class FileUploadController {
    private final GenericFileFacade genericFileFacade;

    public FileUploadController(GenericFileFacade genericFileFacade) {
        this.genericFileFacade = genericFileFacade;
    }

    @PostMapping
    public ApiResponse<GenericFileUploadSessionDTO> create(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateFileUploadRequest request
    ) {
        GenericFileUploadSessionDTO session = genericFileFacade.createSession(new CreateGenericFileUploadCommand(
            TenantContextHolder.currentTenantId(),
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
    public ApiResponse<GenericFileUploadPartDTO> createPart(
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateFileUploadPartRequest request
    ) {
        GenericFileUploadPartDTO part = genericFileFacade.createPart(new CreateGenericFilePartCommand(
            TenantContextHolder.currentTenantId(),
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
    public ApiResponse<GenericFileCompleteDTO> complete(
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CompleteFileUploadRequest request
    ) {
        GenericFileCompleteDTO completed = genericFileFacade.complete(new CompleteGenericFileUploadCommand(
            TenantContextHolder.currentTenantId(),
            uploadId,
            request.fileSha256(),
            request.parts() == null ? List.of() : request.parts().stream()
                .map(part -> new CompleteGenericFileUploadCommand.PartCommand(
                    part.partNumber(),
                    part.partSha256(),
                    part.etag()
                ))
                .toList(),
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(completed, requestId, traceId);
    }

    @PostMapping("/{uploadId}/abort")
    public ApiResponse<Void> abort(
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        genericFileFacade.abort(new AbortGenericFileUploadCommand(
            TenantContextHolder.currentTenantId(),
            uploadId,
            TenantContextHolder.currentUserId(),
            idempotencyKey,
            requestId,
            traceId
        ));
        return ApiResponse.ok(null, requestId, traceId);
    }

    @GetMapping("/{uploadId}")
    public ResponseEntity<ApiResponse<GenericFileUploadSessionDTO>> get(
        @PathVariable String uploadId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return genericFileFacade.get(TenantContextHolder.currentTenantId(), uploadId)
            .map(session -> ResponseEntity.ok(ApiResponse.ok(session, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateFileUploadRequest(
        String fileName,
        String contentType,
        long fileSizeBytes,
        String fileSha256,
        Integer partSizeBytes
    ) {
    }

    public record CreateFileUploadPartRequest(
        int partNumber,
        long sizeBytes,
        String partSha256
    ) {
    }

    public record CompleteFileUploadRequest(
        String fileSha256,
        List<CompleteFileUploadPartRequest> parts
    ) {
    }

    public record CompleteFileUploadPartRequest(
        int partNumber,
        String partSha256,
        String etag
    ) {
    }
}
