from __future__ import annotations

import pytest

from ai_worker.pipeline.audio.preprocess import (
    AudioMetadata,
    AudioPreprocessError,
    FfprobeAudioPreprocessor,
    _metadata_from_ffprobe,
)


def _metadata(codec: str = "pcm_s16le") -> AudioMetadata:
    return AudioMetadata(
        duration_ms=1_000,
        sample_rate_hz=16_000,
        channels=1,
        codec=codec,
        bitrate=256_000,
        format_name="wav",
    )


def test_validate_maps_unknown_codec_to_canonical_code() -> None:
    with pytest.raises(AudioPreprocessError) as exc_info:
        FfprobeAudioPreprocessor._validate(_metadata(codec="unknown"))
    # Canonical registry code is AUDIO_UNSUPPORTED_FORMAT (error-codes.yaml:151),
    # not the drifting AUDIO_FORMAT_UNSUPPORTED.
    assert exc_info.value.error_code == "AUDIO_UNSUPPORTED_FORMAT"


def test_metadata_from_ffprobe_maps_missing_audio_stream_to_audio_corrupted() -> None:
    payload = {
        "streams": [{"codec_type": "video", "codec_name": "h264"}],
        "format": {"duration": "1.0"},
    }
    with pytest.raises(AudioPreprocessError) as exc_info:
        _metadata_from_ffprobe(payload)
    assert exc_info.value.error_code == "AUDIO_CORRUPTED"
