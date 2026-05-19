package com.meeting.api.adapter.internal;

import com.meeting.api.app.speaker.SpeakerReferenceEmbeddingService;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workstation D7 — inbound worker → Java reference-embedding endpoint.
 *
 * <p>Authentication: HMAC with the {@code meeting.ai-worker.hmac-secret} (same
 * secret Java uses outbound for {@code /internal/rerank}). Response carries
 * plaintext L2-normalized centroids; the wire is internal-TLS only and these
 * values MUST NOT be logged. The {@code SpeakerReferenceEmbeddingService}
 * deliberately omits {@code values} from its log lines.
 */
@RestController
public class InternalSpeakerReferenceController {
    private static final Logger log = LoggerFactory.getLogger(InternalSpeakerReferenceController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpeakerReferenceEmbeddingService service;
    private final InternalApiSignatureVerifier verifier;

    public InternalSpeakerReferenceController(
        SpeakerReferenceEmbeddingService service,
        InternalApiSignatureVerifier verifier
    ) {
        this.service = service;
        this.verifier = verifier;
    }

    @PostMapping(value = "/internal/speakers/reference-embeddings", produces = "application/json", consumes = "application/json")
    public ResponseEntity<ApiResponse<Response>> resolve(
        HttpServletRequest request,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("X-Tenant-Id") String tenantHeader,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature
    ) {
        byte[] body;
        try {
            body = request.getInputStream().readAllBytes();
        } catch (IOException ex) {
            return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "could not read body", requestId, traceId);
        }
        // OpenAPI uses /speakers/reference-embeddings under the /internal server prefix;
        // the signing string must include the full /internal/* path that the worker uses.
        String urlPath = request.getRequestURI();
        if (request.getQueryString() != null) {
            urlPath = urlPath + "?" + request.getQueryString();
        }
        try {
            verifier.verify(request.getMethod(), urlPath, body, timestamp, nonce, signature);
        } catch (IllegalArgumentException ex) {
            log.info(
                "speaker_reference_auth_failed tenant={} reason={} requestId={}",
                tenantHeader, ex.getMessage(), requestId
            );
            return error(HttpStatus.UNAUTHORIZED, ErrorCode.CALLBACK_AUTH_FAILED, ex.getMessage(), requestId, traceId);
        }

        Request payload;
        try {
            payload = MAPPER.readValue(body, Request.class);
        } catch (Exception ex) {
            return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "invalid JSON body: " + ex.getMessage(), requestId, traceId);
        }
        if (payload.tenantId == null || payload.tenantId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "tenantId required", requestId, traceId);
        }
        if (!payload.tenantId.equals(tenantHeader)) {
            return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "tenantId mismatch between header and body", requestId, traceId);
        }
        List<String> personIds = payload.personIds == null ? List.of() : payload.personIds;
        if (personIds.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "personIds must be non-empty", requestId, traceId);
        }

        // De-duplicate while preserving the caller's order.
        List<String> deduped = new ArrayList<>(personIds.size());
        Set<String> seen = new HashSet<>();
        for (String p : personIds) {
            if (p != null && !p.isBlank() && seen.add(p)) deduped.add(p);
        }

        List<SpeakerReferenceEmbeddingService.ReferenceEmbedding> resolved;
        try {
            resolved = service.batchByPerson(payload.tenantId, deduped);
        } catch (com.meeting.api.app.common.ApplicationException ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, ex.errorCode(), ex.getMessage(), requestId, traceId);
        }

        List<Item> items = new ArrayList<>(resolved.size());
        for (var r : resolved) {
            items.add(new Item(
                r.personId(),
                toBoxedList(r.values()),
                r.dim(),
                r.hash(),
                r.computedAt()
            ));
        }
        // Loggers below must NEVER mention `values`.
        log.info(
            "speaker_reference_responded tenant={} requested={} resolved={} requestId={}",
            payload.tenantId, deduped.size(), items.size(), requestId
        );
        return ResponseEntity.ok(ApiResponse.ok(new Response(items), requestId, traceId));
    }

    private static List<Double> toBoxedList(float[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (float v : values) out.add((double) v);
        return out;
    }

    private static ResponseEntity<ApiResponse<Response>> error(
        HttpStatus status, ErrorCode code, String message, String requestId, String traceId
    ) {
        return ResponseEntity.status(status).body(new ApiResponse<>(
            false,
            null,
            ErrorInfo.of(code, message, false),
            requestId,
            traceId
        ));
    }

    public record Request(String tenantId, List<String> personIds, String asOf) {
    }

    public record Item(
        String personId,
        List<Double> values,
        int dim,
        String hash,
        OffsetDateTime computedAt
    ) {
    }

    public record Response(List<Item> items) {
    }
}
