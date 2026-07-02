from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
from pathlib import Path
import shutil
from typing import Any

from ai_worker.common.config import settings


class AudioPreprocessError(Exception):
    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


@dataclass(frozen=True)
class AudioMetadata:
    duration_ms: int
    sample_rate_hz: int
    channels: int
    codec: str
    bitrate: int | None
    format_name: str


@dataclass(frozen=True)
class PreprocessResult:
    metadata: AudioMetadata
    channel_map: dict[str, Any]
    quality_warnings: list[str]
    normalized_audio_uri: str
    quality_report: dict[str, Any]
    # Local 16 kHz mono PCM WAV produced by the normalization pass; None when
    # the source already had that shape (or normalization is disabled). The
    # pipeline feeds this single decode to ASR / diarization / speaker so all
    # models share one timeline and the soundfile-based CAM++ path stops
    # failing on compressed uploads.
    normalized_audio_path: Path | None = None


# Target shape every model consumes.
NORMALIZED_SAMPLE_RATE_HZ = 16_000
NORMALIZED_CODEC = "pcm_s16le"


def _needs_normalization(metadata: AudioMetadata) -> bool:
    return (
        metadata.codec.lower() != NORMALIZED_CODEC
        or metadata.sample_rate_hz != NORMALIZED_SAMPLE_RATE_HZ
        or metadata.channels != 1
    )


async def transcode_to_wav16k(
    source: Path,
    target: Path,
    *,
    ffmpeg_binary: str = "ffmpeg",
    timeout_seconds: float | None = None,
) -> None:
    """Decode ``source`` once into 16 kHz mono PCM WAV at ``target``."""
    if shutil.which(ffmpeg_binary) is None:
        raise AudioPreprocessError("AUDIO_PREPROCESS_RUNTIME_MISSING", "ffmpeg is not installed")
    target.parent.mkdir(parents=True, exist_ok=True)
    process = await asyncio.create_subprocess_exec(
        ffmpeg_binary,
        "-nostdin",
        "-v", "error",
        "-y",
        "-i", str(source),
        "-ac", "1",
        "-ar", str(NORMALIZED_SAMPLE_RATE_HZ),
        "-c:a", NORMALIZED_CODEC,
        str(target),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    budget = timeout_seconds if timeout_seconds is not None else settings.ffmpeg_transcode_timeout_seconds
    try:
        _stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=budget)
    except asyncio.TimeoutError as exc:
        process.kill()
        await process.wait()
        raise AudioPreprocessError(
            "AUDIO_CORRUPTED", f"ffmpeg transcode timed out after {budget}s"
        ) from exc
    if process.returncode != 0:
        message = stderr.decode("utf-8", errors="replace").strip()
        raise AudioPreprocessError(
            "AUDIO_CORRUPTED", message or "ffmpeg failed to transcode audio"
        )


class FfprobeAudioPreprocessor:
    def __init__(self, ffprobe_binary: str = "ffprobe", ffmpeg_binary: str = "ffmpeg") -> None:
        self.ffprobe_binary = ffprobe_binary
        self.ffmpeg_binary = ffmpeg_binary

    async def preprocess(self, audio_path: Path, audio_uri: str, channel_map: dict[str, Any] | None) -> PreprocessResult:
        metadata = await self.probe(audio_path)
        self._validate(metadata)
        # Quality signals describe the ORIGINAL upload; the normalized copy is
        # a decode product, not what the user provided.
        effective_channel_map = _channel_map(metadata, channel_map)
        quality_warnings = _quality_warnings(metadata)

        normalized_path: Path | None = None
        effective_metadata = metadata
        if settings.audio_normalize_enabled and _needs_normalization(metadata):
            normalized_path = audio_path.with_name(audio_path.name + ".norm16k.wav")
            await transcode_to_wav16k(
                audio_path, normalized_path, ffmpeg_binary=self.ffmpeg_binary
            )
            # Downstream consumers (e.g. CAM++ frame slicing) compute offsets
            # from this metadata, so it must describe the file they will read.
            effective_metadata = AudioMetadata(
                duration_ms=metadata.duration_ms,
                sample_rate_hz=NORMALIZED_SAMPLE_RATE_HZ,
                channels=1,
                codec=NORMALIZED_CODEC,
                bitrate=None,
                format_name="wav",
            )

        report = {
            "audioUri": audio_uri,
            "durationMs": metadata.duration_ms,
            "sampleRateHz": metadata.sample_rate_hz,
            "channels": metadata.channels,
            "codec": metadata.codec,
            "bitrate": metadata.bitrate,
            "formatName": metadata.format_name,
            "channelMap": effective_channel_map,
            "qualityWarnings": quality_warnings,
            "normalized": normalized_path is not None,
        }
        return PreprocessResult(
            metadata=effective_metadata,
            channel_map=effective_channel_map,
            quality_warnings=quality_warnings,
            normalized_audio_uri=audio_uri,
            quality_report=report,
            normalized_audio_path=normalized_path,
        )

    async def probe(self, audio_path: Path) -> AudioMetadata:
        if not audio_path.exists():
            raise AudioPreprocessError("AUDIO_OBJECT_NOT_FOUND", f"audio object not found: {audio_path}")
        if shutil.which(self.ffprobe_binary) is None:
            raise AudioPreprocessError("AUDIO_PREPROCESS_RUNTIME_MISSING", "ffprobe is not installed")

        process = await asyncio.create_subprocess_exec(
            self.ffprobe_binary,
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(audio_path),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=settings.ffprobe_timeout_seconds
            )
        except asyncio.TimeoutError as exc:
            # A hung ffprobe (pathological/truncated input) must not stall the
            # event loop or the step forever — kill it and fail terminally.
            # AUDIO_CORRUPTED is a Java-known ErrorCode (a metadata probe that
            # can't finish in the cap almost always means unreadable input).
            process.kill()
            await process.wait()
            raise AudioPreprocessError(
                "AUDIO_CORRUPTED",
                f"ffprobe timed out after {settings.ffprobe_timeout_seconds}s",
            ) from exc
        if process.returncode != 0:
            message = stderr.decode("utf-8", errors="replace").strip()
            raise AudioPreprocessError("AUDIO_CORRUPTED", message or "ffprobe failed to read audio")

        try:
            payload = json.loads(stdout.decode("utf-8"))
            return _metadata_from_ffprobe(payload)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise AudioPreprocessError("AUDIO_CORRUPTED", "ffprobe output is invalid") from exc

    @staticmethod
    def _validate(metadata: AudioMetadata) -> None:
        if metadata.duration_ms > 4 * 60 * 60 * 1000:
            raise AudioPreprocessError("AUDIO_TOO_LONG", "audio duration exceeds 4 hours")
        if metadata.sample_rate_hz < 16000:
            raise AudioPreprocessError("AUDIO_SAMPLE_RATE_TOO_LOW", "audio sample rate is below 16kHz")
        if metadata.codec.lower() in {"unknown", ""}:
            raise AudioPreprocessError("AUDIO_FORMAT_UNSUPPORTED", "audio codec is unsupported")


def _metadata_from_ffprobe(payload: dict[str, Any]) -> AudioMetadata:
    audio_stream = next(
        (
            stream for stream in payload.get("streams", [])
            if stream.get("codec_type") == "audio"
        ),
        None,
    )
    if audio_stream is None:
        # A container ffprobe can parse but that has no audio track (video-only
        # file, image, subtitle sidecar). Without the default this raised a
        # bare StopIteration that bypassed the stable error-code mapping, so
        # the user saw a generic failure instead of "file has no audio track".
        raise AudioPreprocessError(
            "AUDIO_FORMAT_UNSUPPORTED", "no audio stream found in file"
        )
    format_info = payload.get("format", {})
    duration_seconds = float(audio_stream.get("duration") or format_info.get("duration") or 0)
    bit_rate_raw = audio_stream.get("bit_rate") or format_info.get("bit_rate")
    return AudioMetadata(
        duration_ms=max(0, round(duration_seconds * 1000)),
        sample_rate_hz=int(audio_stream["sample_rate"]),
        channels=int(audio_stream.get("channels") or 1),
        codec=str(audio_stream.get("codec_name") or "unknown"),
        bitrate=int(bit_rate_raw) if bit_rate_raw else None,
        format_name=str(format_info.get("format_name") or ""),
    )


def _channel_map(metadata: AudioMetadata, provided: dict[str, Any] | None) -> dict[str, Any]:
    if provided and provided.get("channelCount"):
        return provided
    return {
        "channelCount": metadata.channels,
        "layout": "mono" if metadata.channels == 1 else "multi",
        "channels": [
            {"index": index, "label": f"channel_{index}"}
            for index in range(metadata.channels)
        ],
    }


def _quality_warnings(metadata: AudioMetadata) -> list[str]:
    warnings: list[str] = []
    if metadata.channels > 2:
        warnings.append("AUDIO_MULTI_CHANNEL")
    if metadata.bitrate is not None and metadata.bitrate < 32000:
        warnings.append("AUDIO_LOW_BITRATE")
    return warnings
