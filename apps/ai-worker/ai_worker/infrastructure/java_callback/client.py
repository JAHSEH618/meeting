from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone

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
        body_str = httpx._compat.json_dumps(body) if body else "{}"
        headers = self._build_headers(
            method, f"/internal{path}", body_str,
            task_id, attempt_no, trace_id, idempotency_key,
        )

        url = f"{self.base_url}{path}"
        last_error: str | None = None

        for _ in range(max_retries):
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

        return CallbackResponse(
            http_status=0,
            accepted=False,
            error_code="WRITEBACK_FAILED",
            body={"message": last_error},
        )

    async def update_step(
        self,
        task_id: str,
        step_name: str,
        attempt_no: int,
        status: str,
        progress: int = 0,
        error_code: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/steps/{step_name}"
        idempotency_key = f"{task_id}:{step_name}:{attempt_no}:v1"
        body = {
            "tenantId": "tenant_placeholder",
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
        meeting_id: str,
        attempt_no: int,
        transcript_version: int,
        segments: list[dict],
        metadata: dict | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        path = f"/internal/processing-tasks/{task_id}/transcript"
        idempotency_key = f"{task_id}:transcript:{attempt_no}:v1"
        body = {
            "tenantId": "tenant_placeholder",
            "meetingId": meeting_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "transcriptVersion": transcript_version,
            "segments": segments,
            "metadata": metadata or {},
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def complete_worker_phase(
        self,
        task_id: str,
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
            "tenantId": "tenant_placeholder",
            "meetingId": meeting_id,
            "taskId": task_id,
            "attemptNo": attempt_no,
            "phase": "WORKER_DAG",
            "status": status,
            "completedSteps": [{"stepName": s} for s in completed_steps],
            "skippedSteps": skipped_steps or [],
            "finishedAt": datetime.now(timezone.utc).isoformat(),
        }
        return await self._request("POST", path, body, task_id, attempt_no, trace_id, idempotency_key)

    async def fail_task(
        self,
        task_id: str,
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
            "tenantId": "tenant_placeholder",
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
