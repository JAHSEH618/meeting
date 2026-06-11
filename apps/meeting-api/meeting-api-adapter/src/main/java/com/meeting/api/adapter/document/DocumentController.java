package com.meeting.api.adapter.document;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.document.CreateDocumentCommand;
import com.meeting.api.client.document.DocumentDTO;
import com.meeting.api.client.document.DocumentFacade;
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
public class DocumentController {
    private final DocumentFacade facade;

    public DocumentController(DocumentFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/documents")
    public ResponseEntity<ApiResponse<DocumentDTO>> create(
        @RequestBody CreateDocumentRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        DocumentDTO dto = facade.create(new CreateDocumentCommand(
            TenantContextHolder.currentTenantId(),
            body.title(),
            body.fileId(),
            body.documentType(),
            body.contentHash(),
            userId,
            requestId,
            traceId,
            idempotencyKey
        ));
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    @GetMapping("/api/documents")
    public ResponseEntity<ApiResponse<List<DocumentDTO>>> list(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        var items = facade.list(TenantContextHolder.currentTenantId());
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentDTO>> get(
        @PathVariable String documentId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return facade.get(TenantContextHolder.currentTenantId(), documentId)
            .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/documents/{documentId}")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable String documentId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        facade.delete(TenantContextHolder.currentTenantId(), documentId, userId);
        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @PostMapping("/api/documents/{documentId}/reindex")
    public ResponseEntity<ApiResponse<DocumentDTO>> reindex(
        @PathVariable String documentId,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        DocumentDTO dto = facade.reindex(TenantContextHolder.currentTenantId(), documentId, userId);
        return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
    }

    public record CreateDocumentRequest(
        String title,
        String fileId,
        String documentType,
        String contentHash
    ) {
    }
}
