from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Any


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass
class WorkflowStepSnapshot:
    stepName: str
    status: str
    progress: int
    updatedAt: str
    errorCode: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "stepName": self.stepName,
            "status": self.status,
            "progress": self.progress,
            "updatedAt": self.updatedAt,
        }
        if self.errorCode:
            data["errorCode"] = self.errorCode
        return data


@dataclass
class WorkflowSnapshot:
    taskId: str
    workflowId: str
    taskType: str
    tenantId: str
    status: str
    attemptNo: int
    traceId: str
    steps: list[WorkflowStepSnapshot] = field(default_factory=list)
    startedAt: str | None = None
    updatedAt: str | None = None
    completedAt: str | None = None
    errorCode: str | None = None
    errorMessage: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "taskId": self.taskId,
            "workflowId": self.workflowId,
            "taskType": self.taskType,
            "tenantId": self.tenantId,
            "status": self.status,
            "attemptNo": self.attemptNo,
            "traceId": self.traceId,
            "startedAt": self.startedAt,
            "updatedAt": self.updatedAt,
            "completedAt": self.completedAt,
            "errorCode": self.errorCode,
            "errorMessage": self.errorMessage,
            "steps": [step.to_dict() for step in self.steps],
        }


class InMemoryWorkflowStateStore:
    """In-process workflow snapshots, bounded by an LRU cap.

    ``complete()``/``fail()`` only flip status — they never delete — so without
    a cap the long-lived consumer process accumulated one snapshot per task it
    ever handled (a steady memory leak). The cap evicts the oldest entries once
    exceeded; recent tasks (and anything still RUNNING within the window) stay
    queryable via ``/internal/workflows/{taskId}``.
    """

    def __init__(self, max_entries: int = 2048) -> None:
        self._lock = RLock()
        self._workflows: "OrderedDict[str, WorkflowSnapshot]" = OrderedDict()
        self._max_entries = max_entries

    def start(
        self,
        *,
        task_id: str,
        task_type: str,
        tenant_id: str,
        attempt_no: int,
        trace_id: str,
        steps: list[str],
    ) -> WorkflowSnapshot:
        now = utc_now_iso()
        snapshot = WorkflowSnapshot(
            taskId=task_id,
            workflowId=f"wf_{task_id}_{attempt_no}",
            taskType=task_type,
            tenantId=tenant_id,
            status="RUNNING",
            attemptNo=attempt_no,
            traceId=trace_id,
            steps=[
                WorkflowStepSnapshot(stepName=step, status="PENDING", progress=0, updatedAt=now)
                for step in steps
            ],
            startedAt=now,
            updatedAt=now,
        )
        with self._lock:
            self._workflows[task_id] = snapshot
            self._workflows.move_to_end(task_id)
            while len(self._workflows) > self._max_entries:
                self._workflows.popitem(last=False)
        return snapshot

    def update_step(
        self,
        task_id: str,
        step_name: str,
        status: str,
        progress: int,
        error_code: str | None = None,
    ) -> WorkflowSnapshot | None:
        now = utc_now_iso()
        with self._lock:
            snapshot = self._workflows.get(task_id)
            if snapshot is None:
                return None
            for step in snapshot.steps:
                if step.stepName == step_name:
                    step.status = status
                    step.progress = progress
                    step.updatedAt = now
                    step.errorCode = error_code
                    break
            snapshot.updatedAt = now
            return snapshot

    def complete(self, task_id: str, status: str = "SUCCEEDED") -> WorkflowSnapshot | None:
        now = utc_now_iso()
        with self._lock:
            snapshot = self._workflows.get(task_id)
            if snapshot is None:
                return None
            snapshot.status = status
            snapshot.updatedAt = now
            snapshot.completedAt = now
            return snapshot

    def fail(self, task_id: str, error_code: str, error_message: str) -> WorkflowSnapshot | None:
        now = utc_now_iso()
        with self._lock:
            snapshot = self._workflows.get(task_id)
            if snapshot is None:
                return None
            snapshot.status = "FAILED"
            snapshot.errorCode = error_code
            snapshot.errorMessage = error_message
            snapshot.updatedAt = now
            snapshot.completedAt = now
            return snapshot

    def get(self, task_id: str) -> WorkflowSnapshot | None:
        with self._lock:
            return self._workflows.get(task_id)

    def clear(self) -> None:
        with self._lock:
            self._workflows.clear()


workflow_state_store = InMemoryWorkflowStateStore()
