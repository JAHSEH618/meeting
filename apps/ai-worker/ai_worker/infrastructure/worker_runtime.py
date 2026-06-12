"""WorkerRuntime port and local phase 2 runtime implementation."""

import asyncio
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
from ai_worker.infrastructure.artifact_store import build_artifact_store
from ai_worker.infrastructure.java_callback.client import CallbackResponse, JavaCallbackClient
from ai_worker.infrastructure.speaker.reference_client import build_default_client as build_speaker_reference_client
from ai_worker.infrastructure.task_consumer import consume_and_validate
from ai_worker.model_runtime.registry import (
    get_asr_runtime,
    get_bge_m3,
    get_diarization_runtime,
)
from ai_worker.pipeline.speaker.submit import (
    SpeakerCandidateSubmission,
    submit_and_clear_speaker_enrollment_embedding,
    submit_and_clear_speaker_candidates,
)

logger = logging.getLogger(__name__)

# D1 locked decision: heartbeat every 20s per running step; progress is
# monotonically non-decreasing with a floor of 1 (the worker has no
# intra-step progress source). TTL stays 120s on the Java side.
HEARTBEAT_INTERVAL_SECONDS = 20.0
HEARTBEAT_MIN_PROGRESS = 1


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
        heartbeat_interval_seconds: float | None = None,
    ) -> None:
        self.callback_client = callback_client or JavaCallbackClient()
        self.state_store = state_store
        self.heartbeat_interval_seconds = (
            heartbeat_interval_seconds
            if heartbeat_interval_seconds is not None
            else HEARTBEAT_INTERVAL_SECONDS
        )
        # Phase J / final-check A1 — when caller doesn't override the engine,
        # construct LocalAudioPipelineEngine with the registry-resolved ASR
        # and diarization runtimes so AI_WORKER_USE_FAKE_ASR_RUNTIME=false
        # and AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false actually take
        # effect. Previously the default LocalAudioPipelineEngine(state_store)
        # silently fell back to DeterministicAsrRuntime / SingleSpeaker —
        # the flag flip had no observable consequence.
        self.workflow_engine = workflow_engine or LocalAudioPipelineEngine(
            state_store,
            artifact_store=build_artifact_store(),
            asr_runtime=get_asr_runtime(),
            diarization_runtime=get_diarization_runtime(),
            speaker_reference_supplier=build_speaker_reference_client(),
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

        try:
            if is_embedding_task(task):
                return await self._consume_embedding_message(task)
            return await self._consume_audio_message(task)
        except WorkerPipelineError as exc:
            # Raised outside execute_step (start_pipeline / complete_pipeline /
            # submit helpers) — same handling as a failed step result.
            await self._fail_for_pipeline_result(
                task,
                StepResult(
                    step_name=exc.step_name,
                    status="FAILED",
                    progress=100,
                    error_code=exc.error_code,
                    error_message=str(exc),
                    retryable=exc.retryable,
                ),
            )
            return task
        except Exception as exc:  # noqa: BLE001 — D4: Java must learn about every crash before ack/reject
            logger.exception("WORKER_INTERNAL_ERROR: task_id=%s", task.task_id)
            self.state_store.fail(task.task_id, "WORKER_INTERNAL_ERROR", str(exc))
            kwargs: dict[str, Any] = {
                "task_id": task.task_id,
                "tenant_id": task.tenant_id,
                "attempt_no": task.attempt_no,
                "failed_step": task.pipeline_steps[-1] if task.pipeline_steps else "AUDIO_PREPROCESS",
                "error_code": "WORKER_INTERNAL_ERROR",
                "error_message": f"{type(exc).__name__}: {exc}",
                "retryable": True,
                "trace_id": task.trace_id,
                "meeting_id": task.meeting_id,
            }
            if speaker_enrollment_id := _speaker_enrollment_id_for_task(task):
                kwargs["speaker_enrollment_id"] = speaker_enrollment_id
            await self.callback_client.fail_task(**kwargs)
            return task

    async def _consume_audio_message(self, task: TaskMessage) -> TaskMessage:
        context = self.workflow_engine.start_pipeline(task)
        for step_name in task.pipeline_steps:
            if task.task_type == "SPEAKER_ENROLLMENT" and step_name == "SPEAKER_MATCHING":
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, "NOT_REQUIRED_FOR_ENROLLMENT")
                _add_skipped_step(context, step_name, "NOT_REQUIRED_FOR_ENROLLMENT")
                continue
            skip_reason = self._step_skip_reason(task, step_name)
            if skip_reason is not None:
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, skip_reason)
                _add_skipped_step(context, step_name, skip_reason)
                continue
            result = await self.execute_step(task, step_name, context)
            if result.status == "FAILED":
                if result.error_code == "WRITEBACK_FAILED":
                    await self._fail_for_writeback(task, result.step_name, result.error_message or "callback writeback failed")
                else:
                    await self._fail_for_pipeline_result(task, result)
                return task

        artifact = await self.workflow_engine.complete_pipeline(context)

        artifacts_payload = _artifacts_from_context(context)
        if artifacts_payload:
            artifacts_response = await self.callback_client.submit_artifacts(
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                attempt_no=task.attempt_no,
                artifacts=artifacts_payload,
                artifact_manifest_id=artifact.artifact_manifest_id,
                trace_id=task.trace_id,
            )
            if not artifacts_response.accepted:
                await self._fail_for_writeback(task, task.pipeline_steps[-1], "artifacts callback failed")
                return task

        if task.task_type == "SPEAKER_ENROLLMENT":
            response = await self._submit_speaker_enrollment_embedding(task, context)
            if not response.accepted:
                await self._fail_for_writeback(task, "SPEAKER_EMBEDDING", "speaker enrollment callback failed")
                return task

        speaker_submissions = _speaker_submissions_from_context(context)
        if speaker_submissions and task.task_type != "SPEAKER_ENROLLMENT":
            submit_speakers_response = await submit_and_clear_speaker_candidates(
                self.callback_client,
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                attempt_no=task.attempt_no,
                submissions=speaker_submissions,
                meeting_id=task.meeting_id,
                trace_id=task.trace_id,
            )
            if not submit_speakers_response.accepted:
                await self._fail_for_writeback(task, "SPEAKER_MATCHING", "speaker-candidates callback failed")
                return task

        if task.meeting_id and "TRANSCRIPT_MERGE" in task.pipeline_steps:
            transcript_response = await self.callback_client.submit_transcript(
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                meeting_id=task.meeting_id,
                attempt_no=task.attempt_no,
                transcript_version=_transcript_version_for_task(task),
                segments=artifact.transcript_segments,
                metadata={
                    "workflowId": f"wf_{task.task_id}_{task.attempt_no}",
                    "mode": "phase2-local",
                },
                artifact_manifest_id=artifact.artifact_manifest_id,
                trace_id=task.trace_id,
            )
            if not transcript_response.accepted:
                await self._fail_for_writeback(task, "TRANSCRIPT_MERGE", "transcript callback failed")
                return task

        complete_kwargs = {
            "task_id": task.task_id,
            "tenant_id": task.tenant_id,
            "meeting_id": task.meeting_id or "",
            "attempt_no": task.attempt_no,
            "status": artifact.terminal_status,
            "completed_steps": _completed_steps_for_worker_phase(task, context),
            "skipped_steps": _skipped_steps_from_context(context),
            "trace_id": task.trace_id,
        }
        if speaker_enrollment_id := _speaker_enrollment_id_for_task(task):
            complete_kwargs["speaker_enrollment_id"] = speaker_enrollment_id
        complete_response = await self.callback_client.complete_worker_phase(**complete_kwargs)
        if not complete_response.accepted:
            await self._fail_for_writeback(task, task.pipeline_steps[-1], "complete callback failed")
            return task

        self.state_store.complete(task.task_id, artifact.terminal_status)
        return task

    async def _submit_speaker_enrollment_embedding(self, task: TaskMessage, context: Any) -> CallbackResponse:
        speaker_profile_id = task.speaker_profile_id
        speaker_enrollment_id = task.speaker_enrollment_id
        audio_file_id = task.audio_file_id
        embedding = _speaker_enrollment_embedding_from_context(context)
        if not speaker_profile_id or not speaker_enrollment_id or not audio_file_id or embedding is None:
            return CallbackResponse(http_status=0, accepted=False, error_code="WRITEBACK_FAILED")
        return await submit_and_clear_speaker_enrollment_embedding(
            self.callback_client,
            task_id=task.task_id,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            speaker_profile_id=speaker_profile_id,
            speaker_enrollment_id=speaker_enrollment_id,
            audio_file_id=audio_file_id,
            embedding=embedding,
            trace_id=task.trace_id,
        )

    def _step_skip_reason(self, task: TaskMessage, step_name: str) -> str | None:
        hook = getattr(self.workflow_engine, "step_skip_reason", None)
        if hook is None:
            return None
        return hook(task, step_name)

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

            heartbeat_task = asyncio.create_task(self._heartbeat_loop(task, step_name))
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
                        retryable=exc.retryable,
                    ),
                )
                return task
            finally:
                heartbeat_task.cancel()
                try:
                    await heartbeat_task
                except asyncio.CancelledError:
                    pass

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

        heartbeat_task = asyncio.create_task(self._heartbeat_loop(task, step_name))
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
                retryable=exc.retryable,
            )
        except Exception as exc:  # noqa: BLE001 — D4: attribute unexpected step crashes precisely
            logger.exception(
                "WORKER_INTERNAL_ERROR in step: task_id=%s step=%s", task.task_id, step_name
            )
            self.state_store.update_step(task.task_id, step_name, "FAILED", 100, "WORKER_INTERNAL_ERROR")
            return StepResult(
                step_name=step_name,
                status="FAILED",
                progress=100,
                error_code="WORKER_INTERNAL_ERROR",
                error_message=f"{type(exc).__name__}: {exc}",
                retryable=True,
            )
        finally:
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass

        succeeded = await self._update_step(task, step_name, "SUCCEEDED", 100)
        if not succeeded.accepted:
            return self._writeback_failed(step_name, "step success callback failed")

        return StepResult(step_name=step_name, status="SUCCEEDED", progress=100)

    async def _heartbeat_loop(self, task: TaskMessage, step_name: str) -> None:
        """Send RUNNING(progress>=1) heartbeats forever until cancelled.

        Heartbeat failures are logged and swallowed — they must never fail
        the step (D1). The stable idempotency key in JavaCallbackClient makes
        these latest-wins updates on the Java side (no callback_events rows).
        """
        progress = HEARTBEAT_MIN_PROGRESS
        while True:
            await asyncio.sleep(self.heartbeat_interval_seconds)
            try:
                response = await self.callback_client.update_step(
                    task_id=task.task_id,
                    tenant_id=task.tenant_id,
                    step_name=step_name,
                    attempt_no=task.attempt_no,
                    status="RUNNING",
                    progress=progress,
                    trace_id=task.trace_id,
                    meeting_id=task.meeting_id,
                )
                if response.accepted:
                    self.state_store.update_step(task.task_id, step_name, "RUNNING", progress)
                else:
                    logger.warning(
                        "heartbeat rejected: task_id=%s step=%s http=%s error=%s",
                        task.task_id, step_name, response.http_status, response.error_code,
                    )
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 — heartbeat must never break the step
                logger.warning(
                    "heartbeat failed: task_id=%s step=%s", task.task_id, step_name, exc_info=True
                )

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
            meeting_id=task.meeting_id,
        )
        if response.accepted:
            self.state_store.update_step(task.task_id, step_name, status, progress)
        return response

    async def _fail_for_writeback(self, task: TaskMessage, failed_step: str, message: str) -> None:
        logger.error("WRITEBACK_FAILED: task_id=%s step=%s message=%s", task.task_id, failed_step, message)
        self.state_store.update_step(task.task_id, failed_step, "FAILED", 100, "WRITEBACK_FAILED")
        self.state_store.fail(task.task_id, "WRITEBACK_FAILED", message)
        kwargs = {
            "task_id": task.task_id,
            "tenant_id": task.tenant_id,
            "attempt_no": task.attempt_no,
            "failed_step": failed_step,
            "error_code": "WRITEBACK_FAILED",
            "error_message": message,
            "retryable": True,
            "trace_id": task.trace_id,
            "meeting_id": task.meeting_id,
        }
        if speaker_enrollment_id := _speaker_enrollment_id_for_task(task):
            kwargs["speaker_enrollment_id"] = speaker_enrollment_id
        await self.callback_client.fail_task(**kwargs)

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
        kwargs = {
            "task_id": task.task_id,
            "tenant_id": task.tenant_id,
            "attempt_no": task.attempt_no,
            "failed_step": result.step_name,
            "error_code": error_code,
            "error_message": message,
            "retryable": result.retryable,
            "trace_id": task.trace_id,
            "meeting_id": task.meeting_id,
        }
        if speaker_enrollment_id := _speaker_enrollment_id_for_task(task):
            kwargs["speaker_enrollment_id"] = speaker_enrollment_id
        await self.callback_client.fail_task(**kwargs)

    @staticmethod
    def _writeback_failed(step_name: str, message: str) -> StepResult:
        return StepResult(
            step_name=step_name,
            status="FAILED",
            progress=100,
            error_code="WRITEBACK_FAILED",
            error_message=message,
        )


def _speaker_submissions_from_context(context: Any) -> list[SpeakerCandidateSubmission]:
    if isinstance(context, dict):
        submissions = context.get("speaker_submissions", [])
    else:
        submissions = getattr(context, "speaker_submissions", [])
    if not isinstance(submissions, list):
        return []
    return [s for s in submissions if isinstance(s, SpeakerCandidateSubmission)]


def _speaker_enrollment_embedding_from_context(context: Any) -> Any | None:
    if isinstance(context, dict):
        embeddings = context.get("speaker_embeddings", [])
    else:
        embeddings = getattr(context, "speaker_embeddings", [])
    if not isinstance(embeddings, list) or not embeddings:
        return None
    return embeddings[0]


def _speaker_enrollment_id_for_task(task: TaskMessage) -> str | None:
    if task.task_type != "SPEAKER_ENROLLMENT":
        return None
    return task.speaker_enrollment_id


def _transcript_version_for_task(task: TaskMessage) -> int:
    expected = task.expected_input_version if isinstance(task.expected_input_version, dict) else {}
    version = expected.get("transcriptVersion")
    if type(version) is int:
        return version + 1
    return 1


def _add_skipped_step(context: Any, step_name: str, reason: str) -> None:
    skipped_step = {"stepName": step_name, "reason": reason}
    if isinstance(context, dict):
        context.setdefault("skipped_steps", []).append(skipped_step)
        return
    skipped_steps = getattr(context, "skipped_steps", None)
    if isinstance(skipped_steps, list):
        skipped_steps.append(skipped_step)


def _skipped_steps_from_context(context: Any) -> list[dict[str, str]]:
    if isinstance(context, dict):
        skipped_steps = context.get("skipped_steps", [])
    else:
        skipped_steps = getattr(context, "skipped_steps", [])
    if not isinstance(skipped_steps, list):
        return []
    return [s for s in skipped_steps if isinstance(s, dict)]


def _completed_steps_for_worker_phase(task: TaskMessage, context: Any) -> list[str]:
    skipped = {s.get("stepName") for s in _skipped_steps_from_context(context)}
    return [step for step in task.pipeline_steps if step not in skipped]


def _artifacts_from_context(context: Any) -> list[dict[str, Any]]:
    if isinstance(context, dict):
        artifacts = context.get("artifacts", [])
    else:
        artifacts = getattr(context, "artifacts", [])
    if not isinstance(artifacts, list):
        return []
    return [a for a in artifacts if isinstance(a, dict)]
