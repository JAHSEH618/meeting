from __future__ import annotations

import secrets
import asyncio
import random
from dataclasses import dataclass
from datetime import datetime, timezone

import json

import httpx

from ai_worker.common.config import settings
from ai_worker.common.hmac_signing import compute_signature


@dataclass
class CallbackResponse:
    http_status: int
    accepted: bool
    error_code: str | None = None
    body: dict | None = None


# First backoff base (seconds) for budget-bounded writeback retries; grows
# exponentially up to ``callback_retry_max_backoff_seconds``. Module-level so
# tests can shrink it.
_WRITEBACK_BACKOFF_INITIAL = 0.5


class JavaCallbackClient:
    def __init__(self, base_url: str | None = None) -> None:
        self.base_url = (base_url or settings.meeting_api_base_url).rstrip("/")
        self.worker_id = settings.worker_id
        self.hmac_secret = settings.callback_hmac_secret
        self._max_retries = settings.callback_max_retries
        self._writeback_retry_budget = settings.callback_writeback_retry_budget_seconds
        self._max_backoff = settings.callback_retry_max_backoff_seconds
        self._http_client: httpx.AsyncClient | None = None

    def _client(self) -> httpx.AsyncClient:
        if self._http_client is None:
            self._http_client = httpx.AsyncClient(timeout=30)
        return self._http_client

    async def aclose(self) -> None:
        if self._http_client is not None:
            await self._http_client.aclose()
            self._http_client = None

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
        return compute_signature(self.hmac_secret, method, path, body.encode(), timestamp, nonce)

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
        max_retries: int | None = None,
        retry_budget_seconds: float | None = None,
    ) -> CallbackResponse:
        """Send one signed callback with retries on transport failures.

        Two retry modes:
        - default: at most ``max_retries`` quick attempts (sub-second total) —
          right for heartbeats, whose next emission supersedes a lost one.
        - ``retry_budget_seconds``: keep retrying with capped exponential
          backoff until the budget elapses — for result writebacks whose loss
          discards finished (GPU) work. A plain meeting-api rolling restart
          must not be enough to throw a completed pipeline away.
        """
        body_str = json.dumps(body, separators=(",", ":"), sort_keys=True) if body else "{}"
        url = f"{self.base_url}{path}"
        attempts = max_retries if max_retries is not None else self._max_retries
        last_error: str | None = None
        loop = asyncio.get_running_loop()
        deadline = None if retry_budget_seconds is None else loop.time() + retry_budget_seconds

        attempt_index = 0
        while True:
            headers = self._build_headers(
                method, path, body_str,
                task_id, attempt_no, trace_id, idempotency_key,
            )
            try:
                response = await self._client().request(method, url, content=body_str, headers=headers)
                status = response.status_code
                if status < 400:
                    return CallbackResponse(
                        http_status=status,
                        accepted=True,
                        body=response.json(),
                    )
                # Honor the server's retryable signal from the error envelope
                # instead of hardcoding terminal-by-status. Java now returns 401
                # (retryable on clock skew) for auth and 409 retryable=true for
                # transient version/lease conflicts; only genuine idempotency
                # replays / permanent 4xx are terminal. Retrying a terminal 4xx
                # only burns attempts and hammers a recovering API.
                retryable, server_code = _parse_error_envelope(response)
                if not retryable and status not in (408, 429):
                    if status == 409:
                        return CallbackResponse(
                            http_status=409, accepted=False,
                            error_code=server_code or "CALLBACK_IDEMPOTENCY_CONFLICT",
                        )
                    if status == 401:
                        return CallbackResponse(
                            http_status=401, accepted=False,
                            error_code=server_code or "CALLBACK_AUTH_FAILED",
                        )
                    if 400 <= status < 500:
                        return CallbackResponse(
                            http_status=status, accepted=False,
                            error_code=server_code or "CALLBACK_REJECTED",
                        )
                # Server-flagged retryable, 408/429, or 5xx → fall through to retry.
                last_error = f"HTTP {status}" + (f" {server_code}" if server_code else "")
            except Exception as e:
                last_error = str(e)
            # Exponential backoff with full jitter so a fleet of workers
            # retrying a recovering API don't synchronise into a thundering herd.
            if deadline is None:
                if attempt_index >= attempts - 1:
                    break
                base = 0.05 * (2 ** attempt_index)
                delay = base + random.uniform(0.0, base)
            else:
                base = min(self._max_backoff, _WRITEBACK_BACKOFF_INITIAL * (2 ** attempt_index))
                delay = base + random.uniform(0.0, base)
                if loop.time() + delay > deadline:
                    break
            await asyncio.sleep(delay)
            attempt_index += 1

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
        meeting_id: str | None = None,
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/steps/{step_name}"
        idempotency_key = _step_update_idempotency_key(
            task_id=task_id,
            step_name=step_name,
            status=status,
            attempt_no=attempt_no,
            progress=progress,
        )
        body = {
            "tenantId": tenant_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "stepName": step_name,
            "status": status,
            "progress": progress,
        }
        if meeting_id:
            body["meetingId"] = meeting_id
        if error_code:
            body["errorCode"] = error_code
        # Heartbeats (RUNNING, progress>0) keep the quick attempt-count retry:
        # a lost one is superseded by the next emission. State transitions get
        # the writeback budget so a brief Java outage doesn't fail the task.
        is_heartbeat = status == "RUNNING" and progress > 0
        return await self._request(
            "PATCH", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=None if is_heartbeat else self._writeback_retry_budget,
        )

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
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

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
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

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
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

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
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

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
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

    async def complete_worker_phase(
        self,
        task_id: str,
        tenant_id: str,
        meeting_id: str,
        attempt_no: int,
        status: str,
        completed_steps: list[str],
        skipped_steps: list[dict[str, str]] | None = None,
        speaker_enrollment_id: str | None = None,
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
        }
        if speaker_enrollment_id:
            body["speakerEnrollmentId"] = speaker_enrollment_id
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )

    async def fail_task(
        self,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        failed_step: str,
        error_code: str,
        error_message: str,
        retryable: bool = True,
        speaker_enrollment_id: str | None = None,
        trace_id: str = "",
        meeting_id: str | None = None,
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
        }
        if meeting_id:
            body["meetingId"] = meeting_id
        if speaker_enrollment_id:
            body["speakerEnrollmentId"] = speaker_enrollment_id
        return await self._request(
            "POST", path, body, task_id, attempt_no, trace_id, idempotency_key,
            retry_budget_seconds=self._writeback_retry_budget,
        )


def _parse_error_envelope(response: httpx.Response) -> tuple[bool, str | None]:
    """Extract ``(retryable, errorCode)`` from the standard error envelope.

    Defaults to ``(False, None)`` for non-JSON / envelope-less bodies, so an
    unparseable error is treated as terminal (status-based) rather than retried.
    """
    try:
        payload = response.json()
    except Exception:  # noqa: BLE001 — any parse failure → treat as no envelope
        return False, None
    error = payload.get("error") if isinstance(payload, dict) else None
    if not isinstance(error, dict):
        return False, None
    return bool(error.get("retryable", False)), error.get("code")


def _step_update_idempotency_key(
    task_id: str,
    step_name: str,
    status: str,
    attempt_no: int,
    progress: int,
) -> str:
    """Generate idempotency key for step updates.

    Heartbeats (RUNNING with progress > 0) use a stable key to allow latest-wins updates.
    First RUNNING, SUCCEEDED, and FAILED use unique keys to prevent duplicate callbacks.
    """
    if status == "RUNNING" and progress > 0:
        # Heartbeat: stable key allows latest-wins update
        return f"{task_id}:{step_name}:heartbeat:{attempt_no}"
    else:
        # State transition: unique key enforces idempotency
        return f"{task_id}:{step_name}:{status}:{attempt_no}:v1"
