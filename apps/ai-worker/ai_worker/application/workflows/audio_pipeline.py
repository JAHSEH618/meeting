from __future__ import annotations

import asyncio
import hashlib
import inspect
import math
from dataclasses import asdict
import json
import logging
from pathlib import Path
from typing import Any, Callable

from ai_worker.common.config import settings
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact, TaskMessage
from ai_worker.infrastructure.artifact_store import ArtifactStore, LocalArtifactStore
from ai_worker.infrastructure.speaker.reference_client import SpeakerReferenceUnavailable
from ai_worker.pipeline.alignment.transcript_merge import merge_transcript_segments
from ai_worker.pipeline.asr.runtime import AsrModelRuntime, AsrRuntimeError, AsrSegment, DeterministicAsrRuntime
from ai_worker.pipeline.audio.preprocess import AudioPreprocessError, FfprobeAudioPreprocessor, PreprocessResult
from ai_worker.pipeline.diarization.runtime import (
    DiarizationRuntime,
    DiarizationRuntimeError,
    SingleSpeakerDiarizationRuntime,
    SpeakerTurn,
)
from ai_worker.pipeline.speaker.matcher import (
    AuthorizedScopeMatcher,
    ReferenceEmbeddingSupplier,
    SpeakerMatcher,
    SpeakerMatchResult,
)
from ai_worker.pipeline.speaker.runtime import (
    DeterministicSpeakerEmbeddingRuntime,
    SpeakerEmbedding,
    SpeakerEmbeddingRuntime,
    SpeakerEmbeddingRuntimeError,
)
from ai_worker.pipeline.speaker.submit import SpeakerCandidateSubmission

logger = logging.getLogger(__name__)


# Preprocess failures that are transient/environmental rather than bad input:
#   * AUDIO_OBJECT_NOT_FOUND — object-store read-after-write lag; the object may
#     appear on a later attempt.
#   * AUDIO_PREPROCESS_RUNTIME_MISSING — ffmpeg/ffprobe absent; a retry on a
#     correctly-provisioned pod can succeed.
# Everything else (corrupt / too-long / low-sample-rate / unsupported codec) is
# deterministic bad input and must stay non-retryable.
_RETRYABLE_PREPROCESS_CODES = frozenset(
    {"AUDIO_OBJECT_NOT_FOUND", "AUDIO_PREPROCESS_RUNTIME_MISSING"}
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
        artifact_store: ArtifactStore | None = None,
        preprocessor: FfprobeAudioPreprocessor | None = None,
        asr_runtime: AsrModelRuntime | None = None,
        diarization_runtime: DiarizationRuntime | None = None,
        speaker_embedding_runtime: SpeakerEmbeddingRuntime | None = None,
        speaker_reference_supplier: ReferenceEmbeddingSupplier | None = None,
        speaker_matcher: SpeakerMatcher | None = None,
    ) -> None:
        self._state_store = state_store
        self._artifact_store: ArtifactStore = artifact_store or LocalArtifactStore()
        self._preprocessor = preprocessor or FfprobeAudioPreprocessor()
        self._asr_runtime = asr_runtime or DeterministicAsrRuntime()
        self._diarization_runtime = diarization_runtime or SingleSpeakerDiarizationRuntime()
        self._speaker_embedding_runtime = speaker_embedding_runtime or DeterministicSpeakerEmbeddingRuntime()
        self._speaker_reference_supplier = speaker_reference_supplier
        self._speaker_matcher = speaker_matcher or AuthorizedScopeMatcher(
            reference_supplier=speaker_reference_supplier,
            min_confidence=settings.speaker_min_confidence,
            top_k=settings.speaker_top_k,
        )

    async def close(self) -> None:
        # Close every owned resource that exposes (a)close — the speaker
        # reference client's pooled httpx client AND the artifact store's TOS
        # connection pool (LocalArtifactStore has neither, so it's a no-op).
        await self._close_resource(self._speaker_reference_supplier)
        await self._close_resource(self._artifact_store)

    @staticmethod
    async def _close_resource(resource: Any) -> None:
        if resource is None:
            return
        close = getattr(resource, "aclose", None) or getattr(resource, "close", None)
        if close is None:
            return
        result = close()
        if inspect.isawaitable(result):
            await result

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
        elif step_name == "ALIGNMENT":
            await self._run_alignment(context)
        elif step_name == "DIARIZATION":
            await self._run_diarization(context)
        elif step_name == "SPEAKER_EMBEDDING":
            await self._run_speaker_embedding(context)
        elif step_name == "SPEAKER_MATCHING":
            await self._run_speaker_matching(context)
        elif step_name == "TRANSCRIPT_MERGE":
            await self._run_transcript_merge(context)
        elif step_name == "RAG_INDEXING":
            await self._run_rag_indexing(context)
        else:
            raise WorkerPipelineError(
                step_name,
                "WORKER_STEP_NOT_IMPLEMENTED",
                f"worker step is required but not implemented by LocalAudioPipelineEngine: {step_name}",
                retryable=False,
            )

    async def complete_pipeline(self, context: "_PipelineContext") -> PipelineArtifact:
        manifest_ref = await self._write_manifest(context)
        return PipelineArtifact(
            task_id=context.task.task_id,
            transcript_segments=context.transcript_segments,
            speaker_candidates=context.speaker_candidates,
            artifact_manifest_id=manifest_ref.uri,
            terminal_status="SUCCEEDED",
        )

    async def _run_audio_preprocess(self, context: "_PipelineContext") -> None:
        audio_uri = _required_audio_uri(context.task)
        try:
            # local_path may download a multi-MB audio object via the blocking
            # TOS SDK; run it off the consumer event loop so it doesn't freeze
            # other in-flight tasks' heartbeats. (LocalArtifactStore is trivial.)
            audio_path = await asyncio.get_running_loop().run_in_executor(
                None, self._artifact_store.local_path, audio_uri
            )
            context.preprocess = await self._preprocessor.preprocess(
                audio_path,
                audio_uri,
                context.task.channel_map,
            )
        except AudioPreprocessError as exc:
            retryable = exc.error_code in _RETRYABLE_PREPROCESS_CODES
            raise WorkerPipelineError("AUDIO_PREPROCESS", exc.error_code, str(exc), retryable=retryable) from exc
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
        # Bias the model toward the words it is most likely to get wrong:
        # participant names + meeting glossary terms from the task message.
        # Passed as an optional kwarg so runtimes/test stubs with the plain
        # 3-arg transcribe() keep working unchanged.
        transcribe_kwargs: dict[str, Any] = {}
        bias_context = _asr_bias_context(context.task)
        if bias_context and _accepts_kwarg(self._asr_runtime.transcribe, "context"):
            transcribe_kwargs["context"] = bias_context
        try:
            context.asr_segments = await self._asr_runtime.transcribe(
                audio_path,
                preprocess.metadata,
                context.task.language,
                **transcribe_kwargs,
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

    async def _run_alignment(self, context: "_PipelineContext") -> None:
        # Forced alignment is a phase-later/optional refinement — no FA model
        # ships in phase 1. ASR already emits per-segment timestamps and
        # TRANSCRIPT_MERGE consumes ``context.asr_segments`` directly, so
        # ALIGNMENT is an explicit pass-through marker (reported SUCCEEDED)
        # rather than a non-retryable WORKER_STEP_NOT_IMPLEMENTED failure that
        # would kill the whole MEETING_FULL_PIPELINE at step 3.
        logger.info(
            "alignment_passthrough task_id=%s reason=FORCED_ALIGNMENT_NOT_ENABLED_PHASE1",
            context.task.task_id,
        )

    async def _run_diarization(self, context: "_PipelineContext") -> None:
        preprocess = _required_preprocess(context)
        audio_path = _required_audio_path(context)
        ensure_loaded = getattr(self._diarization_runtime, "ensure_loaded", None)
        if ensure_loaded is not None:
            try:
                await ensure_loaded()
            except DiarizationRuntimeError as exc:
                raise WorkerPipelineError("DIARIZATION", exc.error_code, str(exc), retryable=True) from exc
        # Thread the task's speaker-count bounds (required by MEETING_FULL_PIPELINE)
        # into diarization. Only pass them when present so runtimes/stubs without
        # the kwargs keep working.
        diar_kwargs: dict[str, int] = {}
        if context.task.min_speakers is not None:
            diar_kwargs["min_speakers"] = context.task.min_speakers
        if context.task.max_speakers is not None:
            diar_kwargs["max_speakers"] = context.task.max_speakers
        try:
            context.speaker_turns = await self._diarization_runtime.diarize(
                audio_path, preprocess.metadata, **diar_kwargs
            )
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

    async def _run_speaker_embedding(self, context: "_PipelineContext") -> None:
        preprocess = await self._ensure_preprocess(context)
        audio_path = _required_audio_path(context)
        ensure_loaded = getattr(self._speaker_embedding_runtime, "ensure_loaded", None)
        if ensure_loaded is not None:
            try:
                await ensure_loaded()
            except SpeakerEmbeddingRuntimeError as exc:
                raise WorkerPipelineError("SPEAKER_EMBEDDING", exc.error_code, str(exc), retryable=True) from exc
        # One aggregated embedding per diarized speaker instead of one per
        # turn: short turns produce unstable voiceprints and a per-turn fanout
        # meant hundreds of inferences (and hundreds of contradictory match
        # candidates) for an hour-long meeting. Speakers are ordered by total
        # speech duration so downstream embeddings[0] is the dominant speaker
        # (what SPEAKER_ENROLLMENT submits as the profile reference).
        try:
            for _speaker_label, turns in _speaker_turn_groups_for_embedding(context):
                per_turn = [
                    await self._speaker_embedding_runtime.embed(
                        audio_path,
                        preprocess.metadata,
                        speaker_turn,
                    )
                    for speaker_turn in turns
                ]
                context.speaker_embeddings.append(
                    _aggregate_speaker_embeddings(per_turn, turns)
                )
        except SpeakerEmbeddingRuntimeError as exc:
            raise WorkerPipelineError("SPEAKER_EMBEDDING", exc.error_code, str(exc), retryable=True) from exc
        if not context.speaker_embeddings:
            raise WorkerPipelineError(
                "SPEAKER_EMBEDDING",
                "SPEAKER_EMBEDDING_FAILED",
                "speaker embedding runtime returned no embeddings",
                retryable=True,
            )

    async def _run_speaker_matching(self, context: "_PipelineContext") -> None:
        if not context.speaker_embeddings:
            raise WorkerPipelineError(
                "SPEAKER_MATCHING",
                "SPEAKER_MATCH_FAILED",
                "speaker matching requires speaker embeddings",
                retryable=True,
            )
        for embedding in context.speaker_embeddings:
            try:
                match = await self._speaker_matcher.match(context.task, embedding)
            except Exception as exc:  # noqa: BLE001 - map model/reference failures to workflow errors
                error_code = (
                    "SPEAKER_REFERENCE_UNAVAILABLE"
                    if isinstance(exc, SpeakerReferenceUnavailable)
                    else "SPEAKER_MATCH_FAILED"
                )
                raise WorkerPipelineError("SPEAKER_MATCHING", error_code, str(exc), retryable=True) from exc
            context.speaker_matches.append(match)
            context.speaker_submissions.append(SpeakerCandidateSubmission(embedding, match))
            context.speaker_candidates.append(_speaker_candidate_summary(match))

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

    async def _run_rag_indexing(self, context: "_PipelineContext") -> None:
        # Transcript RAG indexing is Java-owned and cannot run inline here:
        # Java chunks the transcript and dispatches a SEPARATE TEXT_EMBEDDING
        # task (ChunkingApplicationService -> EmbeddingTaskDispatcher), and the
        # embeddings callback only fills vectors into chunks Java already
        # persisted (EmbeddingsCallbackApplicationService.markEmbeddings). The
        # audio DAG holds no chunkIds at this point, so RAG_INDEXING is a no-op
        # marker step in this engine — it exists only to close the worker phase
        # with the step set Java enqueued. (Wiring transcript auto-indexing on
        # the Java side is tracked separately and is out of scope here.)
        logger.info(
            "rag_indexing_noop task_id=%s reason=TRANSCRIPT_INDEXING_OWNED_BY_JAVA",
            context.task.task_id,
        )

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
                "speakerEmbedding": getattr(self._speaker_embedding_runtime, "model_version", "unknown"),
            },
        }
        return await self._write_json_artifact(task, "manifest", "artifact-manifest.json", manifest)

    async def _ensure_preprocess(self, context: "_PipelineContext") -> PreprocessResult:
        if context.preprocess is None:
            await self._run_audio_preprocess(context)
        return _required_preprocess(context)

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
        self.speaker_embeddings: list[SpeakerEmbedding] = []
        self.speaker_matches: list[SpeakerMatchResult] = []
        self.speaker_submissions: list[SpeakerCandidateSubmission] = []
        self.speaker_candidates: list[dict[str, Any]] = []
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


def _speaker_turns_for_embedding(context: _PipelineContext) -> list[SpeakerTurn]:
    if context.speaker_turns:
        return context.speaker_turns
    preprocess = _required_preprocess(context)
    return [
        SpeakerTurn(
            speaker_label="SPEAKER_00",
            start_ms=0,
            end_ms=max(1, preprocess.metadata.duration_ms),
            confidence=1.0,
        )
    ]


def _turn_duration_ms(turn: SpeakerTurn) -> int:
    return max(0, turn.end_ms - turn.start_ms)


def _speaker_turn_groups_for_embedding(
    context: _PipelineContext,
) -> list[tuple[str, list[SpeakerTurn]]]:
    """Group diarization turns by speaker and pick the turns worth embedding.

    Per speaker: drop turns shorter than ``speaker_min_segment_seconds``
    (sub-3s clips yield unstable voiceprints), keep the
    ``speaker_max_segments_per_speaker`` longest of the rest, and fall back to
    the single longest turn when everything was filtered so no speaker is
    silently dropped. Groups are ordered by the speaker's total speech
    duration (dominant speaker first).
    """
    groups: dict[str, list[SpeakerTurn]] = {}
    for turn in _speaker_turns_for_embedding(context):
        groups.setdefault(turn.speaker_label, []).append(turn)

    min_duration_ms = int(settings.speaker_min_segment_seconds * 1000)
    max_segments = max(1, settings.speaker_max_segments_per_speaker)

    ranked: list[tuple[str, list[SpeakerTurn], int]] = []
    for speaker_label, turns in groups.items():
        eligible = [t for t in turns if _turn_duration_ms(t) >= min_duration_ms]
        if not eligible:
            eligible = [max(turns, key=_turn_duration_ms)]
        eligible.sort(key=_turn_duration_ms, reverse=True)
        chosen = sorted(eligible[:max_segments], key=lambda t: t.start_ms)
        total_ms = sum(_turn_duration_ms(t) for t in turns)
        ranked.append((speaker_label, chosen, total_ms))
    ranked.sort(key=lambda item: item[2], reverse=True)
    return [(speaker_label, chosen) for speaker_label, chosen, _total in ranked]


def _aggregate_speaker_embeddings(
    embeddings: list[SpeakerEmbedding],
    turns: list[SpeakerTurn],
) -> SpeakerEmbedding:
    """Collapse one speaker's per-turn embeddings into a single centroid.

    Each vector is L2-normalized, then averaged weighted by turn duration and
    re-normalized — one stable voiceprint per speaker instead of N noisy,
    mutually contradictory ones. Single-turn speakers pass through untouched.
    """
    if len(embeddings) == 1:
        return embeddings[0]
    dimension = embeddings[0].dimension
    accumulator = [0.0] * dimension
    total_weight = 0.0
    weighted_quality = 0.0
    for embedding, turn in zip(embeddings, turns):
        weight = float(max(1, _turn_duration_ms(turn)))
        norm = math.sqrt(sum(v * v for v in embedding.values)) or 1.0
        for i, value in enumerate(embedding.values):
            accumulator[i] += weight * value / norm
        weighted_quality += weight * embedding.quality_score
        total_weight += weight
    mean = [value / total_weight for value in accumulator]
    norm = math.sqrt(sum(v * v for v in mean)) or 1.0
    values = [value / norm for value in mean]
    checksum = hashlib.sha256(
        ",".join(f"{v:.6f}" for v in values).encode("utf-8")
    ).hexdigest()
    return SpeakerEmbedding(
        speaker_label=embeddings[0].speaker_label,
        values=values,
        dimension=dimension,
        model_version=embeddings[0].model_version,
        checksum=checksum,
        quality_score=weighted_quality / total_weight,
    )


_ASR_BIAS_CONTEXT_MAX_CHARS = 500


def _asr_bias_context(task: TaskMessage) -> str | None:
    """Join participant display names + glossary terms into a hot-word string.

    Capped so an oversized glossary can't blow up the prompt; order keeps
    names first (they matter most for minutes ownership).
    """
    seen: set[str] = set()
    terms: list[str] = []
    for value in [*task.participant_display_names, *task.glossary_terms]:
        term = (value or "").strip()
        if term and term not in seen:
            seen.add(term)
            terms.append(term)
    if not terms:
        return None
    return " ".join(terms)[:_ASR_BIAS_CONTEXT_MAX_CHARS].strip() or None


def _accepts_kwarg(callable_obj: Callable[..., Any], name: str) -> bool:
    try:
        parameters = inspect.signature(callable_obj).parameters
    except (TypeError, ValueError):
        return False
    if name in parameters:
        return True
    return any(p.kind is inspect.Parameter.VAR_KEYWORD for p in parameters.values())


def _speaker_candidate_summary(match: SpeakerMatchResult) -> dict[str, Any]:
    return {
        "speakerLabel": match.speaker_label,
        "candidates": [
            {
                "personId": candidate.person_id,
                "speakerProfileId": candidate.speaker_profile_id,
                "confidence": candidate.confidence,
                "matchStatus": candidate.match_status,
            }
            for candidate in match.candidates
        ],
    }


def _artifact_dict(category: str, uri: str, sha256: str, size_bytes: int | None) -> dict[str, Any]:
    return {
        "category": category,
        "artifactUri": uri,
        "sha256": sha256,
        "sizeBytes": size_bytes,
    }
