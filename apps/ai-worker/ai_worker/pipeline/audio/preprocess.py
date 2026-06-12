from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
from pathlib import Path
import shutil
from typing import Any


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


class FfprobeAudioPreprocessor:
    def __init__(self, ffprobe_binary: str = "ffprobe") -> None:
        self.ffprobe_binary = ffprobe_binary

    async def preprocess(self, audio_path: Path, audio_uri: str, channel_map: dict[str, Any] | None) -> PreprocessResult:
        metadata = await self.probe(audio_path)
        self._validate(metadata)
        effective_channel_map = _channel_map(metadata, channel_map)
        quality_warnings = _quality_warnings(metadata)
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
        }
        return PreprocessResult(
            metadata=metadata,
            channel_map=effective_channel_map,
            quality_warnings=quality_warnings,
            normalized_audio_uri=audio_uri,
            quality_report=report,
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
        stdout, stderr = await process.communicate()
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
            raise AudioPreprocessError("AUDIO_UNSUPPORTED_FORMAT", "audio codec is unsupported")


def _metadata_from_ffprobe(payload: dict[str, Any]) -> AudioMetadata:
    audio_stream = next(
        stream for stream in payload.get("streams", [])
        if stream.get("codec_type") == "audio"
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
