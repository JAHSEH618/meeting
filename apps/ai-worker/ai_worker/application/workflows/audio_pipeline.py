from __future__ import annotations

from dataclasses import asdict
import json
from pathlib import Path
from typing import Any

from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact, TaskMessage
from ai_worker.infrastructure.artifact_store import LocalArtifactStore
from ai_worker.pipeline.alignment.transcript_merge import merge_transcript_segments
from ai_worker.pipeline.asr.runtime import AsrModelRuntime, AsrRuntimeError, AsrSegment, DeterministicAsrRuntime
from ai_worker.pipeline.audio.preprocess import AudioPreprocessError, FfprobeAudioPreprocessor, PreprocessResult
from ai_worker.pipeline.diarization.runtime import (
    DiarizationRuntime,
    DiarizationRuntimeError,
    SingleSpeakerDiarizationRuntime,
    SpeakerTurn,
)


class WorkerPipelineError(Exception):
    def __init__(self, step_name: str, error_code: str, message: str, retryable: bool = True) -> None:
        super().__init__(message)
        self.step_name = step_name
        self.error_code = error_code
        self.retryable = retryable


class LocalAudioPipelineEngine:
    def __init__(
        self,
        state_store: InMemoryWorkflowStateStore,
        artifact_store: LocalArtifactStore | None = None,
        preprocessor: FfprobeAudioPreprocessor | None = None,
        asr_runtime: AsrModelRuntime | None = None,
        diarization_runtime: DiarizationRuntime | None = None,
    ) -> None:
        self._state_store = state_store
        self._artifact_store = artifact_store or LocalArtifactStore()
        self._preprocessor = preprocessor or FfprobeAudioPreprocessor()
        self._asr_runtime = asr_runtime or DeterministicAsrRuntime()
        self._diarization_runtime = diarization_runtime or SingleSpeakerDiarizationRuntime()

    async def run_pipeline(self, task: TaskMessage) -> PipelineArtifact:
        context = self.start_pipeline(task)
        for step_name in task.pipeline_steps:
            await self.run_step(context, step_name)
        return await self.complete_pipeline(context)

    def start_pipeline(self, task: TaskMessage) -> "_PipelineContext":
        self._state_store.start(
            task_id=task.task_id,
            task_type=task.task_type,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            trace_id=task.trace_id,
            steps=list(task.pipeline_steps),
        )
        return _PipelineContext(task=task)

    async def run_step(self, context: "_PipelineContext", step_name: str) -> None:
        if step_name == "AUDIO_PREPROCESS":
            await self._run_audio_preprocess(context)
        elif step_name == "ASR":
            await self._run_asr(context)
        elif step_name == "DIARIZATION":
            await self._run_diarization(context)
        elif step_name == "TRANSCRIPT_MERGE":
            await self._run_transcript_merge(context)
        else:
            context.skipped_steps.append({"stepName": step_name, "reason": "OUT_OF_PHASE2_SCOPE"})

    async def complete_pipeline(self, context: "_PipelineContext") -> PipelineArtifact:
        manifest_ref = await self._write_manifest(context)
        return PipelineArtifact(
            task_id=context.task.task_id,
            transcript_segments=context.transcript_segments,
            artifact_manifest_id=manifest_ref.uri,
            terminal_status="SUCCEEDED",
        )

    async def _run_audio_preprocess(self, context: "_PipelineContext") -> None:
        audio_uri = _required_audio_uri(context.task)
        try:
            audio_path = self._artifact_store.local_path(audio_uri)
            context.preprocess = await self._preprocessor.preprocess(
                audio_path,
                audio_uri,
                context.task.channel_map,
            )
        except AudioPreprocessError as exc:
            raise WorkerPipelineError("AUDIO_PREPROCESS", exc.error_code, str(exc), retryable=False) from exc
        except OSError as exc:
            raise WorkerPipelineError("AUDIO_PREPROCESS", "AUDIO_OBJECT_NOT_FOUND", str(exc), retryable=True) from exc

        context.audio_path = audio_path
        context.normalized_audio_uri = context.preprocess.normalized_audio_uri
        ref = await self._write_json_artifact(
            context.task,
            "quality-report",
            "audio-preprocess-quality.json",
            context.preprocess.quality_report,
        )
        context.artifacts.append(_artifact_dict("QUALITY_REPORT", ref.uri, ref.sha256, ref.size_bytes))

    async def _run_asr(self, context: "_PipelineContext") -> None:
        preprocess = _required_preprocess(context)
        audio_path = _required_audio_path(context)
        # Lazy-load real model weights on first use; fake/deterministic
        # runtimes don't define ensure_loaded() and stay no-op. We surface
        # load failures as ASR step errors via the same error_code path
        # the runtime's own exceptions use. Per-device serialization lives
        # inside the runtime's transcribe() now — see qwen3_asr_runtime.py.
        ensure_loaded = getattr(self._asr_runtime, "ensure_loaded", None)
        if ensure_loaded is not None:
            try:
                await ensure_loaded()
            except AsrRuntimeError as exc:
                raise WorkerPipelineError("ASR", exc.error_code, str(exc), retryable=True) from exc
        try:
            context.asr_segments = await self._asr_runtime.transcribe(
                audio_path,
                preprocess.metadata,
                context.task.language,
            )
        except AsrRuntimeError as exc:
            raise WorkerPipelineError("ASR", exc.error_code, str(exc), retryable=True) from exc
        if not context.asr_segments:
            raise WorkerPipelineError("ASR", "ASR_EMPTY_RESULT", "ASR returned no segments", retryable=True)
        ref = await self._write_json_artifact(
            context.task,
            "asr",
            "asr-raw.json",
            {
                "modelVersion": getattr(self._asr_runtime, "model_version", "unknown"),
                "segments": [asdict(segment) for segment in context.asr_segments],
            },
        )
        context.artifacts.append(_artifact_dict("ASR_RAW", ref.uri, ref.sha256, ref.size_bytes))

    async def _run_diarization(self, context: "_PipelineContext") -> None:
        preprocess = _required_preprocess(context)
        audio_path = _required_audio_path(context)
        ensure_loaded = getattr(self._diarization_runtime, "ensure_loaded", None)
        if ensure_loaded is not None:
            try:
                await ensure_loaded()
            except DiarizationRuntimeError as exc:
                raise WorkerPipelineError("DIARIZATION", exc.error_code, str(exc), retryable=True) from exc
        try:
            context.speaker_turns = await self._diarization_runtime.diarize(audio_path, preprocess.metadata)
        except DiarizationRuntimeError as exc:
            raise WorkerPipelineError("DIARIZATION", exc.error_code, str(exc), retryable=True) from exc
        if not context.speaker_turns:
            raise WorkerPipelineError("DIARIZATION", "DIARIZATION_EMPTY_TURNS", "diarization returned no turns", retryable=True)
        ref = await self._write_json_artifact(
            context.task,
            "diarization",
            "diarization-turns.json",
            {
                "modelVersion": getattr(self._diarization_runtime, "model_version", "unknown"),
                "turns": [asdict(turn) for turn in context.speaker_turns],
            },
        )
        context.artifacts.append(_artifact_dict("DIARIZATION_TURNS", ref.uri, ref.sha256, ref.size_bytes))

    async def _run_transcript_merge(self, context: "_PipelineContext") -> None:
        context.transcript_segments = merge_transcript_segments(
            context.task,
            context.asr_segments,
            context.speaker_turns,
        )
        if not context.transcript_segments:
            raise WorkerPipelineError("TRANSCRIPT_MERGE", "TRANSCRIPT_MERGE_EMPTY", "merge returned no transcript segments", retryable=True)
        ref = await self._write_json_artifact(
            context.task,
            "transcript",
            "transcript-merge.json",
            {"segments": context.transcript_segments},
        )
        context.artifacts.append(_artifact_dict("TRANSCRIPT_MERGE", ref.uri, ref.sha256, ref.size_bytes))

    async def _write_manifest(self, context: "_PipelineContext") -> Any:
        task = context.task
        manifest = {
            "artifactManifestId": f"artifact_manifest_{task.task_id}_{task.attempt_no}",
            "taskId": task.task_id,
            "tenantId": task.tenant_id,
            "meetingId": task.meeting_id,
            "attemptNo": task.attempt_no,
            "pipelineVersion": "phase2-local-v1",
            "chunkStrategyVersion": task.expected_input_version.get("chunkStrategyVersion", "v1"),
            "inputAudioSha256": task.input_audio_sha256,
            "artifacts": context.artifacts,
            "modelVersions": {
                "asr": getattr(self._asr_runtime, "model_version", "unknown"),
                "diarization": getattr(self._diarization_runtime, "model_version", "unknown"),
            },
        }
        return await self._write_json_artifact(task, "manifest", "artifact-manifest.json", manifest)

    async def _write_json_artifact(self, task: TaskMessage, category: str, name: str, payload: dict[str, Any]) -> Any:
        bucket = "meeting-artifacts"
        key = "/".join([
            "tenant",
            task.tenant_id,
            "meeting",
            task.meeting_id or "none",
            "task",
            task.task_id,
            f"attempt-{task.attempt_no}",
            category,
            name,
        ])
        data = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return await self._artifact_store.upload(bucket, key, data, "application/json")


class _PipelineContext:
    def __init__(self, task: TaskMessage) -> None:
        self.task = task
        self.audio_path: Path | None = None
        self.normalized_audio_uri: str | None = None
        self.preprocess: PreprocessResult | None = None
        self.asr_segments: list[AsrSegment] = []
        self.speaker_turns: list[SpeakerTurn] = []
        self.transcript_segments: list[dict[str, Any]] = []
        self.artifacts: list[dict[str, Any]] = []
        self.skipped_steps: list[dict[str, str]] = []


def _required_audio_uri(task: TaskMessage) -> str:
    if task.audio_uri:
        return task.audio_uri
    raise WorkerPipelineError("AUDIO_PREPROCESS", "AUDIO_SOURCE_MISSING", "task audioUri is missing", retryable=False)


def _required_preprocess(context: _PipelineContext) -> PreprocessResult:
    if context.preprocess is None:
        raise WorkerPipelineError("AUDIO_PREPROCESS", "AUDIO_PREPROCESS_MISSING", "audio preprocess has not run", retryable=False)
    return context.preprocess


def _required_audio_path(context: _PipelineContext) -> Path:
    if context.audio_path is None:
        raise WorkerPipelineError("AUDIO_PREPROCESS", "AUDIO_PREPROCESS_MISSING", "audio path is missing", retryable=False)
    return context.audio_path


def _artifact_dict(category: str, uri: str, sha256: str, size_bytes: int | None) -> dict[str, Any]:
    return {
        "category": category,
        "artifactUri": uri,
        "sha256": sha256,
        "sizeBytes": size_bytes,
    }
