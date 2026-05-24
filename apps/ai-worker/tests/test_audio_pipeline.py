from __future__ import annotations

from pathlib import Path
import shutil
import wave

import pytest

from ai_worker.application.workflows.audio_pipeline import LocalAudioPipelineEngine, WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.artifact_store import LocalArtifactStore
from ai_worker.pipeline.asr.runtime import AsrSegment
from ai_worker.pipeline.audio.preprocess import AudioMetadata, PreprocessResult
from ai_worker.pipeline.diarization.runtime import SpeakerTurn


def _task(audio_uri: str) -> TaskMessage:
    return TaskMessage(
        task_id="task_audio_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        meeting_id="meeting_01",
        security_level="INTERNAL",
        attempt_no=1,
        pipeline_steps=("AUDIO_PREPROCESS", "ASR", "DIARIZATION", "TRANSCRIPT_MERGE"),
        expected_input_version={"chunkStrategyVersion": "v1"},
        trace_id="trace_01",
        audio_file_id="file_01",
        audio_uri=audio_uri,
        language="zh",
        channel_map={"channelCount": 1, "layout": "mono"},
        known_participants=[],
        min_speakers=1,
        max_speakers=4,
        options={"inputAudioSha256": "a" * 64, "inputAudioSizeBytes": 32044},
    )


def _write_wav(path: Path, sample_rate: int = 16000, seconds: float = 0.2) -> None:
    frames = int(sample_rate * seconds)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00\x00" * frames)


@pytest.mark.asyncio
async def test_local_audio_pipeline_writes_artifacts_and_transcript(tmp_path: Path) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe is required for audio preprocess smoke")
    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "meeting-audio-auska" / "tenant_01" / "meeting_01" / "upl_01" / "raw"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path)
    audio_path.with_suffix(audio_path.suffix + ".txt").write_text("测试转录文本", encoding="utf-8")

    store = LocalArtifactStore(audio_root)
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore(), artifact_store=store)

    artifact = await engine.run_pipeline(_task("oss://meeting-audio-auska/meeting-audio-auska/tenant_01/meeting_01/upl_01/raw"))

    assert artifact.terminal_status == "SUCCEEDED"
    assert artifact.artifact_manifest_id is not None
    assert artifact.transcript_segments[0]["text"] == "测试转录文本"
    manifest = await store.download_json(artifact.artifact_manifest_id)
    assert manifest["pipelineVersion"] == "phase2-local-v1"
    assert [item["category"] for item in manifest["artifacts"]] == [
        "QUALITY_REPORT",
        "ASR_RAW",
        "DIARIZATION_TURNS",
        "TRANSCRIPT_MERGE",
    ]


@pytest.mark.asyncio
async def test_audio_pipeline_maps_low_sample_rate_to_stable_error(tmp_path: Path) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe is required for audio preprocess smoke")
    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "low-rate.wav"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path, sample_rate=8000)

    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(),
        artifact_store=LocalArtifactStore(audio_root),
    )

    with pytest.raises(WorkerPipelineError) as exc_info:
        await engine.run_pipeline(_task("oss://meeting-audio-auska/low-rate.wav"))

    assert exc_info.value.step_name == "AUDIO_PREPROCESS"
    assert exc_info.value.error_code == "AUDIO_SAMPLE_RATE_TOO_LOW"


@pytest.mark.asyncio
async def test_audio_pipeline_allows_runtime_injection(tmp_path: Path) -> None:
    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "raw.wav"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path)

    class Preprocessor:
        async def preprocess(self, audio_path: Path, audio_uri: str, channel_map):
            return PreprocessResult(
                metadata=AudioMetadata(
                    duration_ms=900,
                    sample_rate_hz=16000,
                    channels=1,
                    codec="pcm_s16le",
                    bitrate=256000,
                    format_name="wav",
                ),
                channel_map={"channelCount": 1, "layout": "mono"},
                quality_warnings=[],
                normalized_audio_uri=audio_uri,
                quality_report={"durationMs": 900},
            )

    class AsrRuntime:
        model_version = "test-asr"

        async def transcribe(self, audio_path: Path, metadata: AudioMetadata, language: str | None):
            return [AsrSegment(start_ms=100, end_ms=800, text="runtime text", confidence=0.91)]

    class DiarizationRuntime:
        model_version = "test-diar"

        async def diarize(self, audio_path: Path, metadata: AudioMetadata):
            return [SpeakerTurn(speaker_label="SPEAKER_03", start_ms=0, end_ms=900, confidence=0.77)]

    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(),
        artifact_store=LocalArtifactStore(audio_root),
        preprocessor=Preprocessor(),
        asr_runtime=AsrRuntime(),
        diarization_runtime=DiarizationRuntime(),
    )

    artifact = await engine.run_pipeline(_task("oss://meeting-audio-auska/raw.wav"))

    assert artifact.transcript_segments == [
        {
            "segmentId": "task_audio_01_seg_0001",
            "startMs": 100,
            "endMs": 800,
            "speakerLabel": "SPEAKER_03",
            "text": "runtime text",
            "asrConfidence": 0.91,
            "diarizationConfidence": 0.77,
            "speakerConfidence": 0.0,
            "timestampPrecision": "SEGMENT",
        }
    ]
