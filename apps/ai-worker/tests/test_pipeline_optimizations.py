"""Tests for the accuracy/efficiency fixes:

- per-speaker embedding aggregation (min-segment filter, top-K, dominant-first)
- ASR hot-word bias context from participantDisplayNames + glossaryTerms
- sentence-level segmentation of monolithic Qwen3-ASR output
- language-tag normalization
- validate_runtime_config production guard
"""

from __future__ import annotations

import math
from pathlib import Path

import pytest

from ai_worker.application.workflows.audio_pipeline import (
    LocalAudioPipelineEngine,
    _aggregate_speaker_embeddings,
    _asr_bias_context,
    _PipelineContext,
    _speaker_turn_groups_for_embedding,
)
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.common.config import (
    FakeRuntimeConfigError,
    Settings,
    settings,
    validate_runtime_config,
)
from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.task_consumer import validate_and_parse_task_message
from ai_worker.model_runtime.asr.qwen3_asr_runtime import (
    _normalize_language,
    _segments_from_item,
)
from ai_worker.pipeline.asr.runtime import AsrSegment
from ai_worker.pipeline.audio.preprocess import AudioMetadata, PreprocessResult
from ai_worker.pipeline.diarization.runtime import SpeakerTurn
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding


def _task(**overrides) -> TaskMessage:
    values = dict(
        task_id="task_opt_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        meeting_id="meeting_01",
        attempt_no=1,
        pipeline_steps=("AUDIO_PREPROCESS", "ASR"),
        expected_input_version={"chunkStrategyVersion": "v1"},
        trace_id="trace_01",
        audio_file_id="file_01",
        audio_uri="tos://bucket/audio.wav",
        language="zh",
    )
    values.update(overrides)
    return TaskMessage(**values)


def _turn(label: str, start_ms: int, end_ms: int) -> SpeakerTurn:
    return SpeakerTurn(speaker_label=label, start_ms=start_ms, end_ms=end_ms, confidence=0.9)


def _metadata(duration_ms: int = 60_000) -> AudioMetadata:
    return AudioMetadata(
        duration_ms=duration_ms,
        sample_rate_hz=16_000,
        channels=1,
        codec="pcm_s16le",
        bitrate=None,
        format_name="wav",
    )


def _preprocess(duration_ms: int = 60_000) -> PreprocessResult:
    return PreprocessResult(
        metadata=_metadata(duration_ms),
        channel_map={"channelCount": 1, "layout": "mono"},
        quality_warnings=[],
        normalized_audio_uri="tos://bucket/audio.wav",
        quality_report={},
    )


# ── per-speaker embedding grouping ─────────────────────────────────────────


def test_speaker_turn_groups_filter_short_turns_and_rank_by_total_duration() -> None:
    context = _PipelineContext(_task())
    context.speaker_turns = [
        # SPEAKER_00: one long turn + one sub-threshold blip
        _turn("SPEAKER_00", 0, 10_000),
        _turn("SPEAKER_00", 12_000, 12_400),
        # SPEAKER_01: dominant speaker overall
        _turn("SPEAKER_01", 20_000, 40_000),
        _turn("SPEAKER_01", 41_000, 49_000),
    ]

    groups = _speaker_turn_groups_for_embedding(context)

    assert [label for label, _turns in groups] == ["SPEAKER_01", "SPEAKER_00"]
    by_label = dict(groups)
    # the 0.4s blip is dropped, the 10s turn stays
    assert [(t.start_ms, t.end_ms) for t in by_label["SPEAKER_00"]] == [(0, 10_000)]
    assert len(by_label["SPEAKER_01"]) == 2


def test_speaker_turn_groups_keep_longest_turn_when_all_below_threshold() -> None:
    context = _PipelineContext(_task())
    context.speaker_turns = [
        _turn("SPEAKER_00", 0, 900),
        _turn("SPEAKER_00", 1_000, 2_500),
    ]

    groups = _speaker_turn_groups_for_embedding(context)

    assert len(groups) == 1
    label, turns = groups[0]
    assert label == "SPEAKER_00"
    assert [(t.start_ms, t.end_ms) for t in turns] == [(1_000, 2_500)]


def test_speaker_turn_groups_cap_segments_per_speaker(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "speaker_max_segments_per_speaker", 2)
    context = _PipelineContext(_task())
    context.speaker_turns = [
        _turn("SPEAKER_00", 0, 5_000),
        _turn("SPEAKER_00", 6_000, 16_000),
        _turn("SPEAKER_00", 17_000, 25_000),
    ]

    groups = _speaker_turn_groups_for_embedding(context)

    _label, turns = groups[0]
    # the two longest turns survive, back in chronological order
    assert [(t.start_ms, t.end_ms) for t in turns] == [(6_000, 16_000), (17_000, 25_000)]


def _embedding(label: str, values: list[float], quality: float = 0.5) -> SpeakerEmbedding:
    return SpeakerEmbedding(
        speaker_label=label,
        values=values,
        dimension=len(values),
        model_version="test-v0",
        checksum="c",
        quality_score=quality,
    )


def test_aggregate_speaker_embeddings_returns_unit_norm_centroid() -> None:
    turns = [_turn("S", 0, 10_000), _turn("S", 20_000, 30_000)]
    embeddings = [
        _embedding("S", [1.0, 0.0], quality=0.4),
        _embedding("S", [0.0, 1.0], quality=0.8),
    ]

    aggregated = _aggregate_speaker_embeddings(embeddings, turns)

    norm = math.sqrt(sum(v * v for v in aggregated.values))
    assert norm == pytest.approx(1.0)
    # equal durations → symmetric centroid and mid-point quality
    assert aggregated.values[0] == pytest.approx(aggregated.values[1])
    assert aggregated.quality_score == pytest.approx(0.6)
    assert aggregated.speaker_label == "S"


def test_aggregate_single_embedding_passthrough() -> None:
    embedding = _embedding("S", [0.6, 0.8])
    assert _aggregate_speaker_embeddings([embedding], [_turn("S", 0, 5_000)]) is embedding


@pytest.mark.asyncio
async def test_run_speaker_embedding_emits_one_embedding_per_speaker() -> None:
    class RecordingEmbeddingRuntime:
        model_version = "recording-v0"

        def __init__(self) -> None:
            self.calls: list[SpeakerTurn] = []

        async def embed(self, audio_path, metadata, speaker_turn) -> SpeakerEmbedding:
            self.calls.append(speaker_turn)
            return _embedding(speaker_turn.speaker_label, [1.0, 0.0, 0.0])

    runtime = RecordingEmbeddingRuntime()
    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(),
        speaker_embedding_runtime=runtime,
    )
    context = _PipelineContext(_task(pipeline_steps=("SPEAKER_EMBEDDING",)))
    context.audio_path = Path("/tmp/does-not-matter.wav")
    context.preprocess = _preprocess()
    context.speaker_turns = [
        _turn("SPEAKER_00", 0, 10_000),
        _turn("SPEAKER_00", 11_000, 20_000),
        _turn("SPEAKER_01", 30_000, 34_000),
        _turn("SPEAKER_01", 35_000, 35_500),  # filtered: below min segment
    ]

    await engine.run_step(context, "SPEAKER_EMBEDDING")

    assert [e.speaker_label for e in context.speaker_embeddings] == ["SPEAKER_00", "SPEAKER_01"]
    # 2 turns embedded for SPEAKER_00, 1 for SPEAKER_01 (blip filtered)
    assert len(runtime.calls) == 3


# ── ASR hot-word bias context ───────────────────────────────────────────────


def test_asr_bias_context_joins_names_then_glossary_and_dedupes() -> None:
    task = _task(
        participant_display_names=["张三", "李四", "张三"],
        glossary_terms=["TOS", "李四", "RAG"],
    )
    assert _asr_bias_context(task) == "张三 李四 TOS RAG"


def test_asr_bias_context_empty_when_no_terms() -> None:
    assert _asr_bias_context(_task()) is None


@pytest.mark.asyncio
async def test_run_asr_passes_context_only_to_runtimes_that_accept_it(tmp_path: Path) -> None:
    class ContextAwareAsr:
        model_version = "ctx-v0"

        def __init__(self) -> None:
            self.seen_context: str | None = None

        async def transcribe(self, audio_path, metadata, language, context=None):
            self.seen_context = context
            return [AsrSegment(start_ms=0, end_ms=1000, text="你好。", confidence=0.9)]

    class LegacyAsr:
        model_version = "legacy-v0"

        async def transcribe(self, audio_path, metadata, language):
            return [AsrSegment(start_ms=0, end_ms=1000, text="你好。", confidence=0.9)]

    task = _task(participant_display_names=["张三"], glossary_terms=["季度预算"])

    for runtime_cls, expected in ((ContextAwareAsr, "张三 季度预算"), (LegacyAsr, None)):
        runtime = runtime_cls()
        engine = LocalAudioPipelineEngine(
            InMemoryWorkflowStateStore(),
            asr_runtime=runtime,
        )
        context = _PipelineContext(task)
        context.audio_path = tmp_path / "a.wav"
        context.preprocess = _preprocess()
        await engine.run_step(context, "ASR")
        assert context.asr_segments
        if expected is not None:
            assert runtime.seen_context == expected


# ── task message parsing of the new contract fields ────────────────────────


def test_task_message_parses_display_names_and_glossary() -> None:
    raw = {
        "taskId": "task_ctx_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_01",
        "meetingId": "mtg_01",
        "audioFileId": "audio_01",
        "audioUri": "tos://bucket/audio_01.wav",
        "attemptNo": 1,
        "pipelineSteps": [
            "AUDIO_PREPROCESS",
            "ASR",
            "ALIGNMENT",
            "DIARIZATION",
            "SPEAKER_EMBEDDING",
            "SPEAKER_MATCHING",
            "TRANSCRIPT_MERGE",
            "RAG_INDEXING",
        ],
        "expectedInputVersion": {"chunkStrategyVersion": "v1"},
        "language": "zh",
        "channelMap": {"channelCount": 1, "layout": "mono"},
        "knownParticipants": ["person_01"],
        "participantDisplayNames": ["张三", "李四"],
        "glossaryTerms": ["TOS", "RAG"],
        "minSpeakers": 1,
        "maxSpeakers": 4,
        "options": {},
        "traceId": "trace_ctx_01",
    }

    task, errors = validate_and_parse_task_message(raw)

    assert errors == []
    assert task is not None
    assert task.participant_display_names == ["张三", "李四"]
    assert task.glossary_terms == ["TOS", "RAG"]
    assert task.known_participants == ["person_01"]


# ── sentence-level segmentation of funasr output ────────────────────────────


def test_segments_from_item_prefers_sentence_info() -> None:
    item = {
        "text": "第一句。第二句。",
        "confidence": 0.7,
        "sentence_info": [
            {"text": "第一句。", "start": 0, "end": 1500},
            {"text": "第二句。", "start": 1600, "end": 3200},
        ],
    }
    segments = _segments_from_item(item, _metadata(4000))
    assert [(s.start_ms, s.end_ms, s.text) for s in segments] == [
        (0, 1500, "第一句。"),
        (1600, 3200, "第二句。"),
    ]


def test_segments_from_item_splits_monolithic_text_on_punctuation() -> None:
    item = {
        "text": "大家好。今天我们讨论三季度预算问题！预算需要下周确认？",
        "timestamp": [[0, 300], [8_700, 9_000]],
    }
    segments = _segments_from_item(item, _metadata(10_000))
    assert len(segments) == 3
    assert segments[0].start_ms == 0
    assert segments[-1].end_ms == 9_000
    # contiguous, strictly increasing boundaries
    for previous, current in zip(segments, segments[1:]):
        assert current.start_ms == previous.end_ms
        assert current.end_ms > current.start_ms
    assert "".join(s.text for s in segments) == item["text"]


def test_segments_from_item_single_sentence_stays_single_segment() -> None:
    item = {"text": "只有一句话没有结束标点", "timestamp": [[0, 500], [4_500, 5_000]]}
    segments = _segments_from_item(item, _metadata(5_000))
    assert len(segments) == 1
    assert segments[0].start_ms == 0
    assert segments[0].end_ms == 5_000


# ── language normalization ──────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("tag", "expected"),
    [
        ("zh-CN", "zh"),
        ("ZH-TW", "zh"),
        ("en-US", "en"),
        ("auto", "auto"),
        ("zh-Hans-CN", "zh"),
        ("fr-FR", "fr"),
    ],
)
def test_normalize_language(tag: str, expected: str) -> None:
    assert _normalize_language(tag) == expected


def test_normalize_language_falls_back_to_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "asr_default_language", "auto")
    assert _normalize_language(None) == "auto"
    assert _normalize_language("  ") == "auto"


# ── fake-runtime production guard ───────────────────────────────────────────


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, allow_insecure_secrets=True, **overrides)


def test_runtime_guard_passes_for_dev_defaults() -> None:
    validate_runtime_config(_settings())


def test_runtime_guard_rejects_tos_with_fake_runtimes() -> None:
    with pytest.raises(FakeRuntimeConfigError) as excinfo:
        validate_runtime_config(
            _settings(
                storage_backend="tos",
                tos_endpoint="https://tos.example",
                tos_region="cn",
                tos_access_key_id="ak",
                tos_access_key_secret="sk",
            )
        )
    assert "AI_WORKER_USE_FAKE_ASR_RUNTIME" in str(excinfo.value)


def test_runtime_guard_rejects_checksum_with_fake_runtimes() -> None:
    with pytest.raises(FakeRuntimeConfigError):
        validate_runtime_config(_settings(qwen3_asr_expected_checksum="sha256:" + "a" * 64))


def test_runtime_guard_allows_explicit_optin() -> None:
    validate_runtime_config(
        _settings(qwen3_asr_expected_checksum="sha256:" + "a" * 64, allow_fake_runtime=True)
    )


def test_runtime_guard_passes_when_all_runtimes_real() -> None:
    validate_runtime_config(
        _settings(
            qwen3_asr_expected_checksum="sha256:" + "a" * 64,
            use_fake_runtime=False,
            use_fake_asr_runtime=False,
            use_fake_diarization_runtime=False,
            use_fake_speaker_runtime=False,
        )
    )


def test_storage_backend_normalized_and_validated() -> None:
    assert _settings(storage_backend=" TOS ").storage_backend == "tos"
    with pytest.raises(Exception):
        _settings(storage_backend="toss")


# ── audio normalization (transcode-to-16k-mono) ────────────────────────────


def test_needs_normalization_truth_table() -> None:
    from ai_worker.pipeline.audio.preprocess import _needs_normalization

    ok = AudioMetadata(60_000, 16_000, 1, "pcm_s16le", None, "wav")
    assert not _needs_normalization(ok)
    assert _needs_normalization(AudioMetadata(60_000, 44_100, 1, "pcm_s16le", None, "wav"))
    assert _needs_normalization(AudioMetadata(60_000, 16_000, 2, "pcm_s16le", None, "wav"))
    assert _needs_normalization(AudioMetadata(60_000, 16_000, 1, "aac", 128_000, "mov,mp4"))


@pytest.mark.asyncio
async def test_preprocess_transcodes_and_rewrites_metadata(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import ai_worker.pipeline.audio.preprocess as preprocess_module
    from ai_worker.pipeline.audio.preprocess import FfprobeAudioPreprocessor

    source = tmp_path / "meeting.m4a"
    source.write_bytes(b"fake")
    original = AudioMetadata(120_000, 44_100, 2, "aac", 128_000, "mov,mp4")

    async def _fake_probe(self, audio_path: Path) -> AudioMetadata:
        return original

    transcoded: list[tuple[Path, Path]] = []

    async def _fake_transcode(src: Path, dst: Path, **_kwargs) -> None:
        transcoded.append((src, dst))
        dst.write_bytes(b"wav")

    monkeypatch.setattr(FfprobeAudioPreprocessor, "probe", _fake_probe)
    monkeypatch.setattr(preprocess_module, "transcode_to_wav16k", _fake_transcode)

    result = await FfprobeAudioPreprocessor().preprocess(source, "tos://b/meeting.m4a", None)

    assert transcoded == [(source, source.with_name("meeting.m4a.norm16k.wav"))]
    assert result.normalized_audio_path == source.with_name("meeting.m4a.norm16k.wav")
    # metadata must describe the file downstream consumers will actually read
    assert result.metadata.sample_rate_hz == 16_000
    assert result.metadata.channels == 1
    assert result.metadata.codec == "pcm_s16le"
    assert result.metadata.duration_ms == original.duration_ms
    # quality report keeps describing the original upload
    assert result.quality_report["sampleRateHz"] == 44_100
    assert result.quality_report["normalized"] is True


@pytest.mark.asyncio
async def test_preprocess_skips_transcode_for_conformant_wav(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ai_worker.pipeline.audio.preprocess import FfprobeAudioPreprocessor

    source = tmp_path / "meeting.wav"
    source.write_bytes(b"fake")

    async def _fake_probe(self, audio_path: Path) -> AudioMetadata:
        return AudioMetadata(120_000, 16_000, 1, "pcm_s16le", None, "wav")

    monkeypatch.setattr(FfprobeAudioPreprocessor, "probe", _fake_probe)

    result = await FfprobeAudioPreprocessor().preprocess(source, "tos://b/meeting.wav", None)

    assert result.normalized_audio_path is None
    assert result.quality_report["normalized"] is False


def _fake_ffmpeg(tmp_path: Path, script_body: str) -> Path:
    """Write an executable stand-in for ffmpeg; ``$last`` is the output path."""
    script = tmp_path / "fake-ffmpeg"
    script.write_text(
        "#!/bin/sh\n"
        'for last in "$@"; do :; done\n'
        f"{script_body}\n"
    )
    script.chmod(0o755)
    return script


@pytest.mark.asyncio
async def test_transcode_success_publishes_target_atomically(tmp_path: Path) -> None:
    from ai_worker.pipeline.audio.preprocess import transcode_to_wav16k

    ffmpeg = _fake_ffmpeg(tmp_path, 'printf RIFF > "$last"\nexit 0')
    source = tmp_path / "in.m4a"
    source.write_bytes(b"fake")
    target = tmp_path / "out" / "in.m4a.norm16k.wav"

    await transcode_to_wav16k(source, target, ffmpeg_binary=str(ffmpeg))

    assert target.read_bytes() == b"RIFF"
    assert list(target.parent.glob("*.part")) == []


@pytest.mark.asyncio
async def test_transcode_failure_leaves_no_partial_output(tmp_path: Path) -> None:
    # ffmpeg 非零退出时,写了一半的输出必须被清掉 — 否则残留的
    # 半截 WAV 会被后续 ffprobe/ASR 当作合法输入。
    from ai_worker.pipeline.audio.preprocess import AudioPreprocessError, transcode_to_wav16k

    ffmpeg = _fake_ffmpeg(tmp_path, 'printf junk > "$last"\nexit 1')
    source = tmp_path / "in.m4a"
    source.write_bytes(b"fake")
    target = tmp_path / "out" / "in.m4a.norm16k.wav"

    with pytest.raises(AudioPreprocessError, match="AUDIO_CORRUPTED|transcode") as excinfo:
        await transcode_to_wav16k(source, target, ffmpeg_binary=str(ffmpeg))

    assert excinfo.value.error_code == "AUDIO_CORRUPTED"
    assert not target.exists()
    assert list(target.parent.glob("*.part")) == []


@pytest.mark.asyncio
async def test_transcode_timeout_kills_ffmpeg_and_cleans_partial_output(tmp_path: Path) -> None:
    from ai_worker.pipeline.audio.preprocess import AudioPreprocessError, transcode_to_wav16k

    ffmpeg = _fake_ffmpeg(tmp_path, 'printf junk > "$last"\nsleep 30\nexit 0')
    source = tmp_path / "in.m4a"
    source.write_bytes(b"fake")
    target = tmp_path / "out" / "in.m4a.norm16k.wav"

    with pytest.raises(AudioPreprocessError) as excinfo:
        await transcode_to_wav16k(
            source, target, ffmpeg_binary=str(ffmpeg), timeout_seconds=0.2
        )

    assert excinfo.value.error_code == "AUDIO_CORRUPTED"
    assert not target.exists()
    assert list(target.parent.glob("*.part")) == []


@pytest.mark.asyncio
async def test_cleanup_pipeline_removes_normalized_file(tmp_path: Path) -> None:
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore())
    context = _PipelineContext(_task())
    normalized = tmp_path / "audio.norm16k.wav"
    normalized.write_bytes(b"wav")
    context.preprocess = PreprocessResult(
        metadata=_metadata(),
        channel_map={"channelCount": 1, "layout": "mono"},
        quality_warnings=[],
        normalized_audio_uri="tos://b/a.wav",
        quality_report={},
        normalized_audio_path=normalized,
    )

    await engine.cleanup_pipeline(context)

    assert not normalized.exists()


class _CacheEvictingStore:
    """Minimal store exposing evict_local_copy, as TosArtifactStore does."""

    def __init__(self) -> None:
        self.evicted: list[str] = []

    def evict_local_copy(self, uri: str) -> None:
        self.evicted.append(uri)


@pytest.mark.asyncio
async def test_cleanup_pipeline_evicts_source_audio_cache_when_cache_disabled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # enable_audio_artifact_cache=False(默认)时,cleanup 必须连同
    # local_path 下载的源音频缓存副本一起回收,否则磁盘按每任务一份
    # 完整录音的速度增长。
    monkeypatch.setattr(settings, "enable_audio_artifact_cache", False)
    store = _CacheEvictingStore()
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), artifact_store=store)
    context = _PipelineContext(_task())
    context.source_audio_path = tmp_path / "cached-source"

    await engine.cleanup_pipeline(context)

    assert store.evicted == [_task().audio_uri]


@pytest.mark.asyncio
async def test_cleanup_pipeline_keeps_source_audio_cache_when_enabled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(settings, "enable_audio_artifact_cache", True)
    store = _CacheEvictingStore()
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), artifact_store=store)
    context = _PipelineContext(_task())
    context.source_audio_path = tmp_path / "cached-source"

    await engine.cleanup_pipeline(context)

    assert store.evicted == []


@pytest.mark.asyncio
async def test_cleanup_pipeline_never_deletes_local_store_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # LocalArtifactStore.local_path 返回真实存储对象而非缓存副本 —
    # 没有 evict_local_copy,清理必须是 no-op,不能删除源文件。
    from ai_worker.infrastructure.artifact_store import LocalArtifactStore

    monkeypatch.setattr(settings, "enable_audio_artifact_cache", False)
    monkeypatch.setattr(settings, "enable_tos_backup", False)
    store = LocalArtifactStore(root=tmp_path)
    ref = await store.upload("bucket", "audio.wav", b"pcm-bytes", "audio/wav")
    source = store.local_path(ref.uri)
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), artifact_store=store)
    context = _PipelineContext(_task(audio_uri=ref.uri))
    context.source_audio_path = source

    await engine.cleanup_pipeline(context)

    assert source.exists()


# ── GPU interactive lane ─────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_interactive_lane_separates_gpu_semaphores(monkeypatch: pytest.MonkeyPatch) -> None:
    from ai_worker.model_runtime import concurrency

    concurrency.reset_for_tests()
    monkeypatch.setattr(settings, "gpu_interactive_lane_enabled", True)
    audio = concurrency.get_device_semaphore("cuda:0")
    interactive = concurrency.get_device_semaphore("cuda:0", lane=concurrency.INTERACTIVE_LANE)
    assert audio is not interactive
    # CPU family ignores the lane
    assert concurrency.get_device_semaphore("cpu") is concurrency.get_device_semaphore(
        "cpu", lane=concurrency.INTERACTIVE_LANE
    )
    concurrency.reset_for_tests()


@pytest.mark.asyncio
async def test_interactive_lane_disabled_shares_semaphore(monkeypatch: pytest.MonkeyPatch) -> None:
    from ai_worker.model_runtime import concurrency

    concurrency.reset_for_tests()
    monkeypatch.setattr(settings, "gpu_interactive_lane_enabled", False)
    assert concurrency.get_device_semaphore("cuda") is concurrency.get_device_semaphore(
        "cuda", lane=concurrency.INTERACTIVE_LANE
    )
    concurrency.reset_for_tests()


# ── cross-attempt artifact reuse ─────────────────────────────────────────────


class _ExplodingAsr:
    model_version = "reuse-v1"

    async def transcribe(self, audio_path, metadata, language, context=None):
        raise AssertionError("inference must not run when a prior-attempt artifact is reusable")


@pytest.mark.asyncio
async def test_run_asr_reuses_previous_attempt_artifact(tmp_path: Path) -> None:
    from ai_worker.infrastructure.artifact_store import LocalArtifactStore

    store = LocalArtifactStore(root=tmp_path)
    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(),
        artifact_store=store,
        asr_runtime=_ExplodingAsr(),
    )
    task = _task(attempt_no=2)
    # attempt-1 artifact on disk, same model version
    key = LocalAudioPipelineEngine._artifact_key(task, "asr", "asr-raw.json", attempt_no=1)
    await store.upload_json(
        "meeting-artifacts",
        key,
        {
            "modelVersion": "reuse-v1",
            "segments": [
                {"start_ms": 0, "end_ms": 1500, "text": "第一句。", "confidence": 0.9},
                {"start_ms": 1500, "end_ms": 3000, "text": "第二句。", "confidence": 0.9},
            ],
        },
    )

    context = _PipelineContext(task)
    context.audio_path = tmp_path / "a.wav"
    context.preprocess = _preprocess()

    await engine.run_step(context, "ASR")

    assert [s.text for s in context.asr_segments] == ["第一句。", "第二句。"]
    # re-written under the current attempt for a consistent per-attempt manifest
    assert any(a["category"] == "ASR_RAW" and "attempt-2" in a["artifactUri"] for a in context.artifacts)


@pytest.mark.asyncio
async def test_run_asr_recomputes_on_model_version_mismatch(tmp_path: Path) -> None:
    from ai_worker.infrastructure.artifact_store import LocalArtifactStore

    class CountingAsr:
        model_version = "new-v2"

        def __init__(self) -> None:
            self.calls = 0

        async def transcribe(self, audio_path, metadata, language, context=None):
            self.calls += 1
            return [AsrSegment(start_ms=0, end_ms=1000, text="重算。", confidence=0.9)]

    store = LocalArtifactStore(root=tmp_path)
    runtime = CountingAsr()
    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(), artifact_store=store, asr_runtime=runtime
    )
    task = _task(attempt_no=2)
    key = LocalAudioPipelineEngine._artifact_key(task, "asr", "asr-raw.json", attempt_no=1)
    await store.upload_json(
        "meeting-artifacts", key,
        {"modelVersion": "old-v1", "segments": [{"start_ms": 0, "end_ms": 1, "text": "旧", "confidence": 0.5}]},
    )

    context = _PipelineContext(task)
    context.audio_path = tmp_path / "a.wav"
    context.preprocess = _preprocess()

    await engine.run_step(context, "ASR")

    assert runtime.calls == 1
    assert [s.text for s in context.asr_segments] == ["重算。"]


# ── silence-aligned chunked ASR ──────────────────────────────────────────────


def _write_speech_wav(path: Path, pattern: list[tuple[bool, float]], sample_rate: int = 16_000) -> int:
    """pattern: list of (is_speech, seconds). Returns total duration in ms."""
    import struct
    import wave as wave_module

    frames = bytearray()
    for is_speech, seconds in pattern:
        count = int(sample_rate * seconds)
        for i in range(count):
            value = int(8000 * math.sin(i * 0.3)) if is_speech else 0
            frames += struct.pack("<h", value)
    with wave_module.open(str(path), "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(sample_rate)
        writer.writeframes(bytes(frames))
    return int(sum(seconds for _s, seconds in pattern) * 1000)


def test_find_silence_cut_points_prefers_silence_midpoints(tmp_path: Path) -> None:
    from ai_worker.pipeline.audio.chunking import find_silence_cut_points_ms

    wav = tmp_path / "long.wav"
    # speech 1.7s | silence 0.6s | speech 1.7s | silence 0.6s | speech 2.4s
    duration_ms = _write_speech_wav(
        wav,
        [(True, 1.7), (False, 0.6), (True, 1.7), (False, 0.6), (True, 2.4)],
    )

    cuts = find_silence_cut_points_ms(wav, duration_ms, target_interval_ms=2000, search_radius_ms=600)

    assert cuts, "expected at least one cut point"
    # first target (2000ms) sits inside the first silence run (1700-2300ms)
    assert 1700 <= cuts[0] <= 2300


def test_slice_wav_extracts_exact_range(tmp_path: Path) -> None:
    import wave as wave_module

    from ai_worker.pipeline.audio.chunking import slice_wav

    source = tmp_path / "src.wav"
    _write_speech_wav(source, [(True, 3.0)])
    piece = tmp_path / "piece.wav"

    slice_wav(source, piece, 1000, 2000)

    with wave_module.open(str(piece), "rb") as reader:
        assert reader.getframerate() == 16_000
        assert reader.getnframes() == 16_000  # exactly 1 second


@pytest.mark.asyncio
async def test_transcribe_chunked_offsets_segments_and_reports_progress(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ai_worker.pipeline.asr.runtime import AsrRuntimeError

    class PieceAsr:
        model_version = "piece-v0"

        def __init__(self) -> None:
            self.pieces = 0

        async def transcribe(self, audio_path, metadata, language, context=None):
            self.pieces += 1
            if self.pieces == 2:
                # middle piece is silence — must be skipped, not fail the task
                raise AsrRuntimeError("ASR_EMPTY_RESULT", "silence")
            return [AsrSegment(start_ms=100, end_ms=900, text=f"片段{self.pieces}。", confidence=0.9)]

    monkeypatch.setattr(settings, "asr_chunk_target_seconds", 2.0)
    monkeypatch.setattr(settings, "asr_chunk_search_radius_seconds", 0.6)

    wav = tmp_path / "meeting.wav"
    duration_ms = _write_speech_wav(
        wav,
        [(True, 1.7), (False, 0.6), (True, 1.7), (False, 0.6), (True, 2.4)],
    )

    runtime = PieceAsr()
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), asr_runtime=runtime)
    context = _PipelineContext(_task())
    context.audio_path = wav
    context.preprocess = _preprocess(duration_ms)

    await engine.run_step(context, "ASR")

    assert runtime.pieces == 3
    assert len(context.asr_segments) == 2
    # first piece keeps its own offsets; third piece is shifted by its start
    assert context.asr_segments[0].start_ms == 100
    assert context.asr_segments[1].start_ms > 3000
    assert context.step_progress["ASR"] == 100


@pytest.mark.asyncio
async def test_transcribe_chunked_skips_short_audio(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    class SinglePassAsr:
        model_version = "single-v0"

        def __init__(self) -> None:
            self.calls: list[Path] = []

        async def transcribe(self, audio_path, metadata, language, context=None):
            self.calls.append(audio_path)
            return [AsrSegment(start_ms=0, end_ms=1000, text="短音频。", confidence=0.9)]

    monkeypatch.setattr(settings, "asr_chunk_target_seconds", 300.0)
    runtime = SinglePassAsr()
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), asr_runtime=runtime)
    context = _PipelineContext(_task())
    context.audio_path = tmp_path / "short.wav"
    context.preprocess = _preprocess(60_000)  # 1 minute ≪ 300s target

    await engine.run_step(context, "ASR")

    assert runtime.calls == [context.audio_path]
