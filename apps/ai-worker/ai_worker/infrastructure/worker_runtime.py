"""WorkerRuntime port and local phase 2 runtime implementation."""

import logging
import uuid
from typing import Any, Protocol, runtime_checkable

from ai_worker.application.workflows.audio_pipeline import LocalAudioPipelineEngine, WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore, workflow_state_store
from ai_worker.application.workflows.text_embedding import (
    EmbeddingItem,
    TextEmbeddingWorkflow,
    is_embedding_task,
    to_callback_items,
)
from ai_worker.domain.task import StepResult, TaskMessage
from ai_worker.infrastructure.java_callback.client import CallbackResponse, JavaCallbackClient
from ai_worker.infrastructure.task_consumer import consume_and_validate
from ai_worker.model_runtime.registry import (
    get_asr_runtime,
    get_bge_m3,
    get_diarization_runtime,
)

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
    """Executes validated phase 2 task messages with a local audio pipeline."""

    def __init__(
        self,
        callback_client: Any | None = None,
        workflow_engine: Any | None = None,
        embedding_workflow: TextEmbeddingWorkflow | None = None,
        state_store: InMemoryWorkflowStateStore = workflow_state_store,
    ) -> None:
        self.callback_client = callback_client or JavaCallbackClient()
        self.state_store = state_store
        # Phase J / final-check A1 — when caller doesn't override the engine,
        # construct LocalAudioPipelineEngine with the registry-resolved ASR
        # and diarization runtimes so AI_WORKER_USE_FAKE_ASR_RUNTIME=false
        # and AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false actually take
        # effect. Previously the default LocalAudioPipelineEngine(state_store)
        # silently fell back to DeterministicAsrRuntime / SingleSpeaker —
        # the flag flip had no observable consequence.
        self.workflow_engine = workflow_engine or LocalAudioPipelineEngine(
            state_store,
            asr_runtime=get_asr_runtime(),
            diarization_runtime=get_diarization_runtime(),
        )
        self.embedding_workflow = embedding_workflow or TextEmbeddingWorkflow(
            state_store, get_bge_m3()
        )
        self._running = False

    async def start(self) -> None:
        self._running = True

    async def stop(self) -> None:
        self._running = False

    async def consume_message(self, raw_message: dict[str, Any]) -> TaskMessage | None:
        task = await consume_and_validate(raw_message, self.callback_client)
        if task is None:
            return None

        if is_embedding_task(task):
            return await self._consume_embedding_message(task)

        context = self.workflow_engine.start_pipeline(task)
        for step_name in task.pipeline_steps:
            result = await self.execute_step(task, step_name, context)
            if result.status == "FAILED":
                if result.error_code == "WRITEBACK_FAILED":
                    await self._fail_for_writeback(task, result.step_name, result.error_message or "callback writeback failed")
                else:
                    await self._fail_for_pipeline_result(task, result)
                return task

        artifact = await self.workflow_engine.complete_pipeline(context)

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
                    "mode": "phase2-local",
                    "artifactManifestUri": artifact.artifact_manifest_id,
                },
                artifact_manifest_id=None,
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

    async def _consume_embedding_message(self, task: TaskMessage) -> TaskMessage:
        """Run the TEXT_EMBEDDING / RAG_REINDEX path: embed inline chunks
        from the task message, ship the result to Java via
        {@code submit_embeddings}, then close the worker DAG phase.
        """
        context = self.embedding_workflow.start_pipeline(task)
        for step_name in task.pipeline_steps:
            started = await self._update_step(task, step_name, "RUNNING", 0)
            if not started.accepted:
                await self._fail_for_writeback(task, step_name, "step start callback failed")
                return task

            try:
                await self.embedding_workflow.run_step(context, step_name)
            except WorkerPipelineError as exc:
                self.state_store.update_step(task.task_id, step_name, "FAILED", 100, exc.error_code)
                await self._fail_for_pipeline_result(
                    task,
                    StepResult(
                        step_name=exc.step_name,
                        status="FAILED",
                        progress=100,
                        error_code=exc.error_code,
                        error_message=str(exc),
                    ),
                )
                return task

            succeeded = await self._update_step(task, step_name, "SUCCEEDED", 100)
            if not succeeded.accepted:
                await self._fail_for_writeback(task, step_name, "step success callback failed")
                return task

        if context.embeddings:
            submit_response = await self._submit_embeddings(task, context.embeddings, context.model_version)
            if not submit_response.accepted:
                await self._fail_for_writeback(task, "RAG_INDEXING", "embeddings callback failed")
                return task

        artifact = await self.embedding_workflow.complete_pipeline(context)

        complete_response = await self.callback_client.complete_worker_phase(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            meeting_id=task.meeting_id or "",
            attempt_no=task.attempt_no,
            status=artifact.terminal_status,
            completed_steps=list(task.pipeline_steps),
            skipped_steps=context.skipped_steps,
            trace_id=task.trace_id,
        )
        if not complete_response.accepted:
            await self._fail_for_writeback(task, task.pipeline_steps[-1] if task.pipeline_steps else "RAG_INDEXING", "complete callback failed")
            return task

        self.state_store.complete(task.task_id, artifact.terminal_status)
        return task

    async def _submit_embeddings(
        self,
        task: TaskMessage,
        embeddings: list[EmbeddingItem],
        model_version: str,
    ) -> CallbackResponse:
        expected = task.expected_input_version if isinstance(task.expected_input_version, dict) else {}
        chunk_strategy_version = expected.get("chunkStrategyVersion") or "default-zh-v1"
        embedding_batch_id = f"embed_batch_{task.task_id}_{task.attempt_no}_{uuid.uuid4().hex[:12]}"
        source_type = "DOCUMENT" if task.document_id else "PRIMARY_TRANSCRIPT"

        return await self.callback_client.submit_embeddings(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            embedding_batch_id=embedding_batch_id,
            source_type=source_type,
            embedding_model_version=model_version,
            chunk_strategy_version=chunk_strategy_version,
            items=to_callback_items(embeddings),
            trace_id=task.trace_id,
        )

    async def execute_step(self, task: TaskMessage, step_name: str, context: Any | None = None) -> StepResult:
        started = await self._update_step(task, step_name, "RUNNING", 0)
        if not started.accepted:
            return self._writeback_failed(step_name, "step start callback failed")

        if await self._heartbeat(task, step_name, 50) is False:
            return self._writeback_failed(step_name, "step heartbeat callback failed")

        try:
            if context is not None and hasattr(self.workflow_engine, "run_step"):
                await self.workflow_engine.run_step(context, step_name)
        except WorkerPipelineError as exc:
            self.state_store.update_step(task.task_id, step_name, "FAILED", 100, exc.error_code)
            return StepResult(
                step_name=exc.step_name,
                status="FAILED",
                progress=100,
                error_code=exc.error_code,
                error_message=str(exc),
            )

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

    async def _fail_for_pipeline_result(self, task: TaskMessage, result: StepResult) -> None:
        error_code = result.error_code or "PIPELINE_STEP_FAILED"
        message = result.error_message or error_code
        logger.error(
            "PIPELINE_FAILED: task_id=%s step=%s error_code=%s message=%s",
            task.task_id,
            result.step_name,
            error_code,
            message,
        )
        self.state_store.fail(task.task_id, error_code, message)
        await self.callback_client.fail_task(
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            failed_step=result.step_name,
            error_code=error_code,
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
