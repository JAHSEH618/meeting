"""Phase J — generate deterministic staging mock weights.

Stages four small mock weight files under a configurable root so that
:func:`ai_worker.observability.model_checksum.compute_checksum` returns a
stable hash; the script then prints the corresponding
``AI_WORKER_*_MODELS_DIR`` and ``AI_WORKER_*_EXPECTED_CHECKSUM`` env vars
that the runbook ``docs/runbooks/phase-j-acceptance.md`` §J4 expects.

This is staging-only. Real production hashes belong in the
``docs/model-registry.md`` prod table, which stays ``<pending>`` until
the real weights are downloaded, audited and uploaded.

Usage::

    uv run python scripts/stage_mock_weights.py
    uv run python scripts/stage_mock_weights.py --root /opt/models
    uv run python scripts/stage_mock_weights.py --format dotenv > .env.staging
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
from dataclasses import dataclass
from pathlib import Path

# Importing the production helper is intentional — the script must hash
# bytes the same way the runtime does, otherwise the expected checksum we
# emit will never match what ``/internal/models`` computes.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ai_worker.observability.model_checksum import compute_checksum  # noqa: E402


@dataclass(frozen=True)
class ModelFixture:
    name: str  # logical name, matches ``_all_model_infos`` entry
    relative_dir: str  # under the staging root
    weight_files: tuple[str, ...]
    models_dir_env: str
    expected_env: str


FIXTURES: tuple[ModelFixture, ...] = (
    ModelFixture(
        name="bge-m3",
        relative_dir="bge-m3/v1",
        weight_files=("model.safetensors",),
        models_dir_env="AI_WORKER_BGE_M3_MODELS_DIR",
        expected_env="AI_WORKER_BGE_M3_EXPECTED_CHECKSUM",
    ),
    ModelFixture(
        name="bge-reranker-v2-m3",
        relative_dir="bge-reranker-v2-m3/v1",
        weight_files=("model.safetensors",),
        models_dir_env="AI_WORKER_BGE_RERANKER_MODELS_DIR",
        expected_env="AI_WORKER_BGE_RERANKER_EXPECTED_CHECKSUM",
    ),
    ModelFixture(
        name="qwen3-asr-1.7b",
        relative_dir="qwen3-asr-1.7b/v2026.05.1",
        weight_files=("model.safetensors",),
        models_dir_env="AI_WORKER_QWEN3_ASR_MODELS_DIR",
        expected_env="AI_WORKER_QWEN3_ASR_EXPECTED_CHECKSUM",
    ),
    ModelFixture(
        name="pyannote-diarization",
        relative_dir="pyannote/v3.1",
        # config.yaml is intentionally omitted: WEIGHT_SUFFIXES only hashes
        # weight payloads (.safetensors / .bin / .pt / .pth / .gguf / .onnx),
        # so a config file would not contribute to the digest anyway.
        weight_files=("pytorch_model.bin",),
        models_dir_env="AI_WORKER_PYANNOTE_MODELS_DIR",
        expected_env="AI_WORKER_PYANNOTE_EXPECTED_CHECKSUM",
    ),
)


def _deterministic_payload(fixture: ModelFixture, file_name: str) -> bytes:
    """Stable bytes derived from the fixture identity.

    Same fixture + same file → same bytes → same checksum across machines.
    A 4 KiB body is large enough to look real to anyone poking at it and
    small enough that staging a fresh tree costs nothing.
    """
    seed = f"meeting/staging-mock-weights/{fixture.name}/{file_name}".encode()
    body = b""
    counter = 0
    while len(body) < 4096:
        body += hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
        counter += 1
    return body[:4096]


def stage(root: Path, force: bool) -> list[tuple[ModelFixture, str]]:
    results: list[tuple[ModelFixture, str]] = []
    for fixture in FIXTURES:
        target_dir = root / fixture.relative_dir
        target_dir.mkdir(parents=True, exist_ok=True)
        for name in fixture.weight_files:
            target = target_dir / name
            if target.exists() and not force:
                # The payload is deterministic, so overwriting is harmless,
                # but skipping keeps the script idempotent and friendly to
                # accidentally re-running with --force omitted.
                continue
            target.write_bytes(_deterministic_payload(fixture, name))
        checksum = compute_checksum(str(target_dir))
        assert checksum is not None, f"checksum None for {target_dir}"
        results.append((fixture, checksum))
    return results


def emit_shell(root: Path, results: list[tuple[ModelFixture, str]]) -> None:
    print(f"# staging root: {root}")
    print("# source this file (or copy into your shell) before booting ai-worker")
    for fixture, checksum in results:
        target_dir = root / fixture.relative_dir
        print(f'export {fixture.models_dir_env}="{target_dir}"')
        print(f'export {fixture.expected_env}="{checksum}"')


def emit_dotenv(root: Path, results: list[tuple[ModelFixture, str]]) -> None:
    print(f"# staging root: {root}")
    for fixture, checksum in results:
        target_dir = root / fixture.relative_dir
        print(f"{fixture.models_dir_env}={target_dir}")
        print(f"{fixture.expected_env}={checksum}")


def emit_table(root: Path, results: list[tuple[ModelFixture, str]]) -> None:
    print(f"# staging root: {root}\n")
    print("| model | path | sha256 |")
    print("|---|---|---|")
    for fixture, checksum in results:
        target_dir = root / fixture.relative_dir
        print(f"| {fixture.name} | `{target_dir}` | `{checksum}` |")


def _resolve_root(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    env_root = os.environ.get("AI_WORKER_STAGING_MODELS_ROOT")
    if env_root:
        return Path(env_root).resolve()
    # Repo-local default: anyone in the workspace can run this without
    # needing sudo on /opt. Production should mount /opt/models per the
    # K8s manifest.
    repo_root = Path(__file__).resolve().parents[3]
    return repo_root / ".cache" / "staging-models"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None, help="Staging root directory")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-write existing fixture files (default: skip if present)",
    )
    parser.add_argument(
        "--format",
        choices=("shell", "dotenv", "table"),
        default="shell",
        help="Output format for env vars",
    )
    args = parser.parse_args()
    root = _resolve_root(args.root)
    root.mkdir(parents=True, exist_ok=True)
    results = stage(root, force=args.force)
    if args.format == "dotenv":
        emit_dotenv(root, results)
    elif args.format == "table":
        emit_table(root, results)
    else:
        emit_shell(root, results)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
