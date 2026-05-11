package com.meeting.api.adapter.internal;

import com.meeting.api.client.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal callback API — receives ai-worker step status, artifacts, transcript,
 * speaker candidates, embeddings, and terminal task state.
 *
 * ⚠ SECURITY TODO (spec.md §6 — callback 校验清单):
 * This controller is currently a stub that returns accepted:true for every request.
 * Before production use, every endpoint MUST implement the following checks:
 *
 * 1.  HMAC-SHA256 signature verification (X-Signature header)
 * 2.  X-Timestamp skew check (within 5 min)
 * 3.  X-Nonce dedup check (short-term)
 * 4.  X-Attempt-No vs current processing_tasks.attempt_count
 * 5.  X-Lease-Owner vs current processing_tasks.lease_owner
 * 6.  tenantId → taskId relationship (from processing_tasks row)
 * 7.  Idempotency-Key dedup + body-hash check (except heartbeat)
 * 8.  expectedInputVersion validation
 *
 * Without these checks, an attacker on the internal network can inject arbitrary
 * task state, transcript data, or speaker candidates.
 *
 * See docs/spec-fixes.md §A5 for heartbeat idempotency exemption rules.
 */
@RestController
@RequestMapping("/internal/processing-tasks/{taskId}")
public class ProcessingTaskCallbackController {

    @PatchMapping("/steps/{stepName}")
    public ApiResponse<Map<String, Object>> updateStep(
        @PathVariable String taskId,
        @PathVariable String stepName,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease verification per spec §6
        return accepted(taskId, stepName);
    }

    @PostMapping("/artifacts")
    public ApiResponse<Map<String, Object>> artifacts(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease verification per spec §6
        return accepted(taskId, "ARTIFACTS");
    }

    @PostMapping("/transcript")
    public ApiResponse<Map<String, Object>> transcript(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease + tenant/meeting verification per spec §6
        return accepted(taskId, "TRANSCRIPT");
    }

    @PostMapping("/speaker-candidates")
    public ApiResponse<Map<String, Object>> speakerCandidates(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease + tenant/meeting verification per spec §6
        // ⚠ speaker embedding plaintext MUST be encrypted via KMS before persistence;
        //    clear from process memory after callback success or retry exhaustion.
        return accepted(taskId, "SPEAKER_CANDIDATES");
    }

    @PostMapping("/embeddings")
    public ApiResponse<Map<String, Object>> embeddings(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease + tenant verification per spec §6
        return accepted(taskId, "EMBEDDINGS");
    }

    @PostMapping("/complete")
    public ApiResponse<Map<String, Object>> complete(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease + completedSteps validation per spec §6
        return accepted(taskId, "COMPLETE");
    }

    @PostMapping("/fail")
    public ApiResponse<Map<String, Object>> fail(
        @PathVariable String taskId,
        @RequestBody Map<String, Object> payload
    ) {
        // TODO: implement HMAC + attempt + lease + errorCode validation per spec §6
        return accepted(taskId, "FAIL");
    }

    private ApiResponse<Map<String, Object>> accepted(String taskId, String stepName) {
        return ApiResponse.ok(
            Map.of("accepted", true, "taskId", taskId, "stepName", stepName),
            null,
            null
        );
    }
}
