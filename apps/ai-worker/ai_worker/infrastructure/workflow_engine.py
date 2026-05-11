"""WorkflowEngine port — Pipeline DAG orchestration.

Implementation: Prefect 3.x (one-off default).
Alternative: Temporal, Ray.
"""

from typing import Protocol, runtime_checkable

from ai_worker.domain.task import TaskMessage, PipelineArtifact


@runtime_checkable
class WorkflowEngine(Protocol):
    """Orchestrates the full meeting pipeline as a DAG of steps.

    Responsibilities:
    - DAG definition: preprocess -> VAD -> ASR -> diarize -> speaker -> merge
    - Parallelism: diarize and ASR can run concurrently on multi-GPU
    - Error handling: optional step failures downgrade to PARTIAL_SUCCEEDED
    - Cancellation: propagate cancel signal to running steps
    - Observability: emit step durations, RTF, and GPU metrics
    """

    async def run_pipeline(self, task: TaskMessage) -> PipelineArtifact:
        """Execute the full pipeline DAG for a task.

        Returns a PipelineArtifact with:
        - transcript segments
        - speaker candidates
        - artifact manifest references
        - terminal status (SUCCEEDED / PARTIAL_SUCCEEDED / FAILED)
        """
        ...

    async def cancel(self, task_id: str) -> None:
        """Request cancellation of a running pipeline.

        Should propagate to all running steps and trigger CALLBACK_CANCELLED.
        """
        ...
