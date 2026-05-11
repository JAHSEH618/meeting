"""WorkerRuntime port — async task consumption and step execution.

Implementation: Dramatiq 1.17+ (one-off default).
Alternative: Celery, Temporal Worker.
"""

from typing import Protocol, runtime_checkable

from ai_worker.domain.task import TaskMessage, StepResult, StepStatus


@runtime_checkable
class WorkerRuntime(Protocol):
    """Consumes tasks from RabbitMQ and executes individual Pipeline steps.

    The WorkerRuntime owns:
    - Queue binding (audio-cpu, gpu-asr, gpu-diar, gpu-speaker, embed, llm)
    - Step-level retry, lease heartbeat, and callback dispatch
    - Graceful shutdown and orphan handling
    """

    async def start(self) -> None:
        """Start consuming from configured queues. Non-blocking in async context."""
        ...

    async def stop(self) -> None:
        """Graceful shutdown: drain running steps, release leases, flush callbacks."""
        ...

    async def execute_step(
        self, task: TaskMessage, step_name: str
    ) -> StepResult:
        """Execute a single step for the given task.

        Called by the runtime after dequeue. Must:
        1. Claim lease via callback before starting work
        2. Send heartbeat at configured interval
        3. Call ModelRuntime / pipeline functions
        4. Callback step status (running -> completed/failed)
        5. Release lease on terminal state
        """
        ...
