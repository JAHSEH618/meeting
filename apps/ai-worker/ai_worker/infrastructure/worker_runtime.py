"""WorkerRuntime port and MVP fake runtime implementation."""

import logging
from typing import Any, Protocol, runtime_checkable

from ai_worker.application.workflows.fake_engine import FakeSmokeWorkflowEngine
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore, workflow_state_store
from ai_worker.domain.task import PipelineArtifact, StepResult, TaskMessage
from ai_worker.infrastructure.java_callback.client import CallbackResponse, JavaCallbackClient
from ai_worker.infrastructure.task_consumer import consume_and_validate

logger = logging.getLogger(__name__)


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


class MvpWorkerRuntime:
    """Executes validated task messages with a fake smoke workflow.

    The runtime is intentionally small: RabbitMQ adapters hand raw JSON messages
    to ``consume_message``; this class owns validation, callback ordering, and
    workflow status tracking. Java creates the MVP task with a matching default
    lease owner until a dedicated claim endpoint lands.
    """

    def __init__(
        self,
        callback_client: Any | None = None,
        workflow_engine: FakeSmokeWorkflowEngine | None = None,
        state_store: InMemoryWorkflowStateStore = workflow_state_store,
    ) -> None:
        self.callback_client = callback_client or JavaCallbackClient()
        self.state_store = state_store
        self.workflow_engine = workflow_engine or FakeSmokeWorkflowEngine(state_store)
        self._running = False

    async def start(self) -> None:
        self._running = True

    async def stop(self) -> None:
        self._running = False

    async def consume_message(self, raw_message: dict[str, Any]) -> TaskMessage | None:
        task = await consume_and_validate(raw_message, self.callback_client)
        if task is None:
            return None

        artifact = await self.workflow_engine.run_pipeline(task)
        for step_name in task.pipeline_steps:
            result = await self.execute_step(task, step_name)
            if result.status == "FAILED":
                await self._fail_for_writeback(task, result.step_name, result.error_message or "callback writeback failed")
                return task

        if task.meeting_id and "TRANSCRIPT_MERGE" in task.pipeline_steps:
            transcript_response = await self.callback_client.submit_transcript(
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                meeting_id=task.meeting_id,
                attempt_no=task.attempt_no,
                transcript_version=1,
                segments=artifact.transcript_segments,
                metadata={
                    "workflowId": f"wf_{task.task_id}_{task.attempt_no}",
                    "mode": "fake-smoke",
                },
                trace_id=task.trace_id,
            )
            if not transcript_response.accepted:
                await self._fail_for_writeback(task, "TRANSCRIPT_MERGE", "transcript callback failed")
                return task

        complete_response = await self.callback_client.complete_worker_phase(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            meeting_id=task.meeting_id or "",
            attempt_no=task.attempt_no,
            status=artifact.terminal_status,
            completed_steps=list(task.pipeline_steps),
            skipped_steps=[],
            trace_id=task.trace_id,
        )
        if not complete_response.accepted:
            await self._fail_for_writeback(task, task.pipeline_steps[-1], "complete callback failed")
            return task

        self.state_store.complete(task.task_id, artifact.terminal_status)
        return task

    async def execute_step(self, task: TaskMessage, step_name: str) -> StepResult:
        started = await self._update_step(task, step_name, "RUNNING", 0)
        if not started.accepted:
            return self._writeback_failed(step_name, "step start callback failed")

        if await self._heartbeat(task, step_name, 50) is False:
            return self._writeback_failed(step_name, "step heartbeat callback failed")

        succeeded = await self._update_step(task, step_name, "SUCCEEDED", 100)
        if not succeeded.accepted:
            return self._writeback_failed(step_name, "step success callback failed")

        return StepResult(step_name=step_name, status="SUCCEEDED", progress=100)

    async def _update_step(
        self,
        task: TaskMessage,
        step_name: str,
        status: str,
        progress: int,
    ) -> CallbackResponse:
        response = await self.callback_client.update_step(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            step_name=step_name,
            attempt_no=task.attempt_no,
            status=status,
            progress=progress,
            trace_id=task.trace_id,
        )
        if response.accepted:
            self.state_store.update_step(task.task_id, step_name, status, progress)
        return response

    async def _heartbeat(self, task: TaskMessage, step_name: str, progress: int) -> bool:
        response = await self.callback_client.update_step(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            step_name=step_name,
            attempt_no=task.attempt_no,
            status="RUNNING",
            progress=progress,
            trace_id=task.trace_id,
        )
        if response.accepted:
            self.state_store.update_step(task.task_id, step_name, "RUNNING", progress)
        return response.accepted

    async def _fail_for_writeback(self, task: TaskMessage, failed_step: str, message: str) -> None:
        logger.error("WRITEBACK_FAILED: task_id=%s step=%s message=%s", task.task_id, failed_step, message)
        self.state_store.update_step(task.task_id, failed_step, "FAILED", 100, "WRITEBACK_FAILED")
        self.state_store.fail(task.task_id, "WRITEBACK_FAILED", message)
        await self.callback_client.fail_task(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            failed_step=failed_step,
            error_code="WRITEBACK_FAILED",
            error_message=message,
            retryable=True,
            trace_id=task.trace_id,
        )

    @staticmethod
    def _writeback_failed(step_name: str, message: str) -> StepResult:
        return StepResult(
            step_name=step_name,
            status="FAILED",
            progress=100,
            error_code="WRITEBACK_FAILED",
            error_message=message,
        )
