"""CallbackClient port — HMAC-signed internal callback to meeting-api.

Implementation: httpx + HMAC-SHA256 signing.
"""

from typing import Protocol, runtime_checkable, Any
from dataclasses import dataclass


@dataclass
class CallbackResponse:
    http_status: int
    accepted: bool
    error_code: str | None = None
    body: dict[str, Any] | None = None


@runtime_checkable
class CallbackClient(Protocol):
    """Sends structured callbacks to meeting-api via internal HTTPS + HMAC.

    Endpoints:
    - PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
    - POST  /internal/processing-tasks/{taskId}/artifacts
    - POST  /internal/processing-tasks/{taskId}/transcript
    - POST  /internal/processing-tasks/{taskId}/speaker-candidates
    - POST  /internal/processing-tasks/{taskId}/embeddings
    - POST  /internal/processing-tasks/{taskId}/complete
    - POST  /internal/processing-tasks/{taskId}/fail

    All requests carry:
    - HMAC-SHA256 signature
    - Idempotency-Key: {taskId}:{stepName}:{attemptNo}:{payloadVersion}
    - X-Worker-Id, X-Attempt-No, X-Lease-Owner, X-Timestamp, X-Nonce
    - X-Request-Id, X-Trace-Id

    Constraints:
    - Retry on network error (exponential backoff), max 3 retries
    - On 409 (conflict), stop retrying and record WRITEBACK_FAILED
    - Never send plaintext speaker embeddings outside callback channel
    - Clear embedding plaintext references after callback success or retry exhaustion
    """

    async def update_step(
        self,
        task_id: str,
        step_name: str,
        attempt_no: int,
        lease_owner: str,
        status: str,
        progress: int = 0,
        error_code: str | None = None,
    ) -> CallbackResponse:
        """PATCH /internal/processing-tasks/{taskId}/steps/{stepName}"""
        ...

    async def submit_artifacts(
        self,
        task_id: str,
        attempt_no: int,
        artifacts: list[dict[str, Any]],
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/artifacts"""
        ...

    async def submit_transcript(
        self,
        task_id: str,
        meeting_id: str,
        attempt_no: int,
        transcript_version: int,
        segments: list[dict[str, Any]],
        metadata: dict[str, Any],
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/transcript"""
        ...

    async def submit_speaker_candidates(
        self,
        task_id: str,
        meeting_id: str,
        attempt_no: int,
        speaker_candidates: list[dict[str, Any]],
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/speaker-candidates"""
        ...

    async def submit_embeddings(
        self,
        task_id: str,
        attempt_no: int,
        embedding_batch_id: str,
        source_type: str,
        model_version: str,
        chunk_strategy_version: str,
        items: list[dict[str, Any]],
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/embeddings"""
        ...

    async def submit_worker_phase_complete(
        self,
        task_id: str,
        meeting_id: str,
        attempt_no: int,
        status: str,
        completed_steps: list[str],
        skipped_steps: list[dict[str, str]] | None = None,
        phase: str = "WORKER_DAG",
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/complete"""
        ...

    async def fail_task(
        self,
        task_id: str,
        meeting_id: str,
        attempt_no: int,
        failed_step: str,
        error_code: str,
        error_message: str,
        retryable: bool = True,
    ) -> CallbackResponse:
        """POST /internal/processing-tasks/{taskId}/fail"""
        ...
