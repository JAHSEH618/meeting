"""D5 — every code the worker can emit toward Java must exist in
packages/meeting-contracts/schemas/common/error-codes.yaml.

Mirrors tests/test_workflow_registry_contract.py: read the contract file
from the repo root and compare against an explicit, hand-maintained set.
When you add a new raise site, add the code here AND to the YAML registry.
"""

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
ERROR_CODES_YAML = (
    REPO_ROOT / "packages" / "meeting-contracts" / "schemas" / "common" / "error-codes.yaml"
)

WORKER_EMITTED_ERROR_CODES = frozenset({
    # consumer / runtime level
    "INVALID_TASK_MESSAGE",
    "WORKER_INTERNAL_ERROR",
    "WORKER_STEP_NOT_IMPLEMENTED",
    "PIPELINE_STEP_FAILED",
    "WRITEBACK_FAILED",
    # audio preprocess
    "AUDIO_OBJECT_NOT_FOUND",
    "AUDIO_SOURCE_MISSING",
    "AUDIO_PREPROCESS_MISSING",
    "AUDIO_PREPROCESS_RUNTIME_MISSING",
    "AUDIO_CORRUPTED",
    "AUDIO_TOO_LONG",
    "AUDIO_SAMPLE_RATE_TOO_LOW",
    "AUDIO_UNSUPPORTED_FORMAT",
    # asr
    "ASR_EMPTY_RESULT",
    "ASR_RUNTIME_ERROR",
    "ASR_MODEL_TIMEOUT",
    "ASR_MODEL_LOAD_FAILED",
    "ASR_GPU_OOM",
    # diarization
    "DIARIZATION_FAILED",
    "DIARIZATION_EMPTY_TURNS",
    "DIARIZATION_GPU_OOM",
    # speaker
    "SPEAKER_EMBEDDING_FAILED",
    "SPEAKER_EMBEDDING_GPU_OOM",
    "SPEAKER_MATCH_FAILED",
    "SPEAKER_REFERENCE_UNAVAILABLE",
    # transcript merge
    "TRANSCRIPT_MERGE_EMPTY",
    "TRANSCRIPT_MERGE_FAILED",
    # text embedding workflow
    "TEXT_EMBEDDING_NO_CHUNKS",
    "EMBEDDING_MODEL_LOAD_FAILED",
    "EMBEDDING_FAILED",
    "EMBEDDING_DIMENSION_MISMATCH",
    "EMBEDDING_EMPTY_VECTOR",
})

WORKER_SRC = Path(__file__).resolve().parents[1] / "ai_worker"


def _registry_codes() -> set[str]:
    text = ERROR_CODES_YAML.read_text(encoding="utf-8")
    return set(re.findall(r"^\s*-\s*code:\s*([A-Z0-9_]+)\s*$", text, flags=re.M))


def test_worker_emitted_error_codes_are_registered() -> None:
    missing = WORKER_EMITTED_ERROR_CODES - _registry_codes()
    assert not missing, f"unregistered worker error codes: {sorted(missing)}"


def test_drifted_audio_format_code_is_gone_from_source() -> None:
    preprocess_src = (WORKER_SRC / "pipeline" / "audio" / "preprocess.py").read_text()
    assert "AUDIO_FORMAT_UNSUPPORTED" not in preprocess_src
