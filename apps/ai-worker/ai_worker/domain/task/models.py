from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class TaskStepUpdate:
    tenant_id: str
    meeting_id: str
    task_id: str
    step_name: str
    attempt_no: int
    status: str
    progress: int
    heartbeat_at: datetime | None = None
