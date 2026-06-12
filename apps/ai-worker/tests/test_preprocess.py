from __future__ import annotations

import pytest

from ai_worker.pipeline.audio.preprocess import (
    AudioMetadata,
    AudioPreprocessError,
    FfprobeAudioPreprocessor,
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
