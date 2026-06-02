from __future__ import annotations

import hashlib
import hmac
import secrets
import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone

import json

import httpx

from ai_worker.common.config import settings


@dataclass
class CallbackResponse:
    http_status: int
    accepted: bool
    error_code: str | None = None
    body: dict | None = None


class JavaCallbackClient:
    def __init__(self, base_url: str | None = None) -> None:
        self.base_url = (base_url or settings.meeting_api_base_url).rstrip("/")
        self.worker_id = settings.worker_id
        self.hmac_secret = settings.callback_hmac_secret.encode()

    def _generate_nonce(self) -> str:
        return secrets.token_hex(16)

    def _sign(
        self,
        method: str,
        path: str,
        body: str,
        timestamp: str,
        nonce: str,
    ) -> str:
        signing_string = f"{timestamp}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body.encode()).hexdigest()}"
        sig = hmac.new(self.hmac_secret, signing_string.encode(), hashlib.sha256).hexdigest()
        return f"hmac-sha256={sig}"

    def _build_headers(
        self,
        method: str,
        path: str,
        body: str,
        task_id: str,
        attempt_no: int,
        trace_id: str,
        idempotency_key: str,
    ) -> dict[str, str]:
        timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        nonce = self._generate_nonce()
        signature = self._sign(method, path, body, timestamp, nonce)

        return {
            "Content-Type": "application/json",
            "X-Worker-Id": self.worker_id,
            "X-Attempt-No": str(attempt_no),
            "X-Lease-Owner": f"{self.worker_id}:{task_id}:{attempt_no}",
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": signature,
            "X-Trace-Id": trace_id,
            "X-Request-Id": f"{trace_id}:{task_id}",
            "Idempotency-Key": idempotency_key,
        }

    async def _request(
        self,
        method: str,
        path: str,
        body: dict,
        task_id: str,
        attempt_no: int,
        trace_id: str,
        idempotency_key: str,
        max_retries: int = 3,
    ) -> CallbackResponse:
        body_str = json.dumps(body, separators=(",", ":"), sort_keys=True) if body else "{}"
        url = f"{self.base_url}{path}"
        last_error: str | None = None

        for attempt_index in range(max_retries):
            headers = self._build_headers(
                method, path, body_str,
                task_id, attempt_no, trace_id, idempotency_key,
            )
            try:
                async with httpx.AsyncClient(timeout=30) as client:
                    response = await client.request(method, url, content=body_str, headers=headers)
                    if response.status_code == 409:
                        return CallbackResponse(
                            http_status=409,
                            accepted=False,
                            error_code="CALLBACK_IDEMPOTENCY_CONFLICT",
                        )
                    if response.status_code == 401:
                        return CallbackResponse(
                            http_status=401,
                            accepted=False,
                            error_code="CALLBACK_AUTH_FAILED",
                        )
                    if response.status_code < 400:
                        return CallbackResponse(
                            http_status=response.status_code,
                            accepted=True,
                            body=response.json(),
                        )
                    last_error = f"HTTP {response.status_code}"
            except Exception as e:
                last_error = str(e)
            if attempt_index < max_retries - 1:
                await asyncio.sleep(0.05 * (2 ** attempt_index))

        return CallbackResponse(
            http_status=0,
            accepted=False,
            error_code="WRITEBACK_FAILED",
            body={"message": last_error},
        )

    async def update_step(
        self,
        task_id: str,
        tenant_id: str,
        step_name: str,
        attempt_no: int,
        status: str,
        progress: int = 0,
        error_code: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/steps/{step_name}"
        idempotency_key = f"{task_id}:{step_name}:{status}:{attempt_no}:v1"
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "stepName": step_name,
            "status": status,
            "progress": progress,
        }
        if error_code:
            body["errorCode"] = error_code
        return await self._request("PATCH", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def submit_transcript(
        self,
        task_id: str,
        tenant_id: str,
        meeting_id: str,
        attempt_no: int,
        transcript_version: int,
        segments: list[dict],
        metadata: dict | None = None,
        artifact_manifest_id: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/transcript"
        idempotency_key = f"{task_id}:transcript:{attempt_no}:v1"
        body = {
            "tenantId": tenant_id,
            "meetingId": meeting_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "transcriptVersion": transcript_version,
            "segments": segments,
            "metadata": metadata or {},
        }
        if artifact_manifest_id:
            body["artifactManifestId"] = artifact_manifest_id
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def submit_speaker_candidates(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        speaker_candidates: list[dict],
        meeting_id: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        """Send a speaker-candidates callback carrying plaintext embedding.values.

        IMPORTANT: callers must NOT serialize the embedding payload to a TOS / object
        store artifact. Plaintext embeddings only ever travel on this in-process
        callback path under internal TLS + HMAC, and Java envelope-encrypts on receipt.

        The caller is responsible for clearing the `values` list (overwrite with 0.0
        per element) immediately after this method returns — see worker.runtime caller.
        """

        path = f"/internal/processing-tasks/{task_id}/speaker-candidates"
        idempotency_key = f"{task_id}:speaker-candidates:{attempt_no}:v1"
        body: dict = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "speakerCandidates": speaker_candidates,
        }
        if meeting_id:
            body["meetingId"] = meeting_id
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def submit_speaker_enrollment_embedding(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        speaker_profile_id: str,
        speaker_enrollment_id: str,
        audio_file_id: str,
        embedding: dict,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/speaker-enrollment"
        idempotency_key = f"{task_id}:speaker-enrollment:{attempt_no}:{speaker_enrollment_id}:v1"
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "speakerProfileId": speaker_profile_id,
            "speakerEnrollmentId": speaker_enrollment_id,
            "audioFileId": audio_file_id,
            "embedding": embedding,
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def submit_artifacts(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        artifacts: list[dict],
        artifact_manifest_id: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/artifacts"
        idempotency_key = f"{task_id}:artifacts:{attempt_no}:v1"
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "artifacts": artifacts,
        }
        if artifact_manifest_id:
            body["artifactManifestId"] = artifact_manifest_id
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def submit_embeddings(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        embedding_batch_id: str,
        source_type: str,
        embedding_model_version: str,
        chunk_strategy_version: str,
        items: list[dict],
        trace_id: str = "",
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/embeddings.

        Submits a batch of text embeddings for RAG indexing chunks. The Java
        side envelope-encrypts and persists into knowledge_chunks.embedding
        in M5A C13.
        """
        path = f"/internal/processing-tasks/{task_id}/embeddings"
        idempotency_key = f"{task_id}:embeddings:{attempt_no}:{embedding_batch_id}"
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "embeddingBatchId": embedding_batch_id,
            "sourceType": source_type,
            "embeddingModelVersion": embedding_model_version,
            "chunkStrategyVersion": chunk_strategy_version,
            "items": items,
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def complete_worker_phase(
        self,
        task_id: str,
        tenant_id: str,
        meeting_id: str,
        attempt_no: int,
        status: str,
        completed_steps: list[str],
        skipped_steps: list[dict[str, str]] | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/complete"
        idempotency_key = f"{task_id}:complete:{attempt_no}:v1"
        body = {
            "tenantId": tenant_id,
            "meetingId": meeting_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "phase": "WORKER_DAG",
            "status": status,
            "completedSteps": completed_steps,
            "skippedSteps": skipped_steps or [],
            "finishedAt": datetime.now(timezone.utc).isoformat(),
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def fail_task(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        failed_step: str,
        error_code: str,
        error_message: str,
        retryable: bool = True,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/fail"
        idempotency_key = f"{task_id}:fail:{attempt_no}:v1"
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "failedStep": failed_step,
            "error": {
                "code": error_code,
                "message": error_message,
                "retryable": retryable,
            },
            "failedAt": datetime.now(timezone.utc).isoformat(),
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)
