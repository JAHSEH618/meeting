from __future__ import annotations

from dataclasses import asdict

import httpx

from ai_worker.common.config import settings
from ai_worker.domain.task import TaskStepUpdate


class JavaCallbackClient:
    def __init__(self, base_url: str | None = None) -> None:
        self.base_url = (base_url or settings.meeting_api_base_url).rstrip("/")

    async def update_step(self, update: TaskStepUpdate, trace_id: str) -> dict:
        url = f"{self.base_url}/internal/processing-tasks/{update.task_id}/steps/{update.step_name}"
        headers = {
            "X-Worker-Id": settings.worker_id,
            "X-Attempt-No": str(update.attempt_no),
            "X-Lease-Owner": f"{settings.worker_id}:{update.task_id}:{update.attempt_no}",
            "X-Trace-Id": trace_id,
            "X-Request-Id": f"{trace_id}:step:{update.step_name}",
            "Idempotency-Key": f"{update.task_id}:{update.step_name}:attempt_{update.attempt_no}",
        }
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.patch(url, json=asdict(update), headers=headers)
            response.raise_for_status()
            return response.json()
