#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker on Apple Silicon (arm64) — full real-models path
# ─────────────────────────────────────────────────────────────────────────────
# Companion to deploy/DEPLOY.md §五·五.5.2. The Linux/NVIDIA prod path
# uses the CUDA build (`UV_EXTRAS=real-models` + CUDA wheel of torch).
# Apple Silicon cannot use CUDA, but the four real-model extras
# (FlagEmbedding, funasr, pyannote.audio, modelscope) all publish or
# build on arm64 macOS — so we can run the FULL real-models stack
# natively, with the following device split:
#
#   - BGE-m3 / BGE-reranker  → MPS (fp32; MPS fp16 has unstable norm/
#                                   softmax kernels in PyTorch)
#   - Qwen3-ASR              → CPU (funasr's runtime kernels are not
#                                   MPS-clean as of torch 2.5)
#   - pyannote diarization   → CPU (segmentation model uses ops that
#                                   fall back from MPS to CPU anyway,
#                                   running on CPU directly avoids the
#                                   noisy "operator not supported"
#                                   warnings)
#
# Throughput will be 5-15× slower than a single NVIDIA RTX 4080. Use this
# script for development, local acceptance dry-runs, or single-host
# demos — NOT for production traffic.
#
# Usage:
#   ./deploy/ai-worker-apple-silicon.sh stage   # stage mock weights only
#                                               # (offline smoke)
#   ./deploy/ai-worker-apple-silicon.sh weights # download REAL weights
#                                               # (BGE + Qwen3 + pyannote
#                                               #  via HF/ModelScope,
#                                               #  plus CAM++ speaker model)
#   ./deploy/ai-worker-apple-silicon.sh env     # create/reuse local env file
#   ./deploy/ai-worker-apple-silicon.sh run     # uv sync + start API
#   ./deploy/ai-worker-apple-silicon.sh verify  # /internal/hardware +
#                                               # /internal/ready check
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AI_WORKER_DIR="${REPO_ROOT}/apps/ai-worker"
MODELS_DIR="${AI_WORKER_MODELS_ROOT:-${HOME}/meeting-models}"

log()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m⚠\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

require_apple_silicon() {
    if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
        err "This script targets macOS Apple Silicon (arm64). Detected $(uname -sm)."
        err "Linux + NVIDIA: use docker build with UV_EXTRAS=real-models (DEPLOY.md §5·5.2.A)."
        exit 1
    fi
}

ensure_deps() {
    command -v uv >/dev/null 2>&1 || {
        err "uv not found. Install via: brew install uv  (or curl -LsSf https://astral.sh/uv/install.sh | sh)"
        exit 1
    }
    command -v python3 >/dev/null 2>&1 || {
        err "python3 not found. Install via: brew install python@3.11"
        exit 1
    }
    if ! python3 -c 'import sys; assert sys.version_info[:2] == (3, 11)' 2>/dev/null; then
        warn "System python3 is not 3.11 — uv will manage its own interpreter via .tool-versions."
    fi
    command -v ffprobe >/dev/null 2>&1 || {
        err "ffprobe/ffmpeg not found. The audio pipeline's AUDIO_PREPROCESS step shells"
        err "out to ffprobe and funasr/pyannote decode audio via ffmpeg. Install via:"
        err "  brew install ffmpeg"
        exit 1
    }
}

stage_mock_weights() {
    log "Staging deterministic mock weights into ${MODELS_DIR}"
    cd "${AI_WORKER_DIR}"
    uv run python scripts/stage_mock_weights.py \
        --root "${MODELS_DIR}" \
        --force \
        --format=dotenv > "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.checksums"
    log "Mock checksums written to deploy/.ai-worker-apple-silicon.env.checksums"
}

download_real_weights() {
    cd "${AI_WORKER_DIR}"
    if [ "${AI_WORKER_SKIP_UV_SYNC:-0}" = "1" ]; then
        warn "AI_WORKER_SKIP_UV_SYNC=1 set; reusing the current uv environment."
    else
        log "Syncing python environment with all real-model dependencies..."
        uv sync --extra dev --extra real-models
    fi

    log "Starting real weights download into: ${MODELS_DIR}"
    export MODELS_DIR
    mkdir -p "${MODELS_DIR}/bge-m3/v1" \
             "${MODELS_DIR}/bge-reranker-v2-m3/v1" \
             "${MODELS_DIR}/qwen3-asr-1.7b/v2026.05.1" \
             "${MODELS_DIR}/qwen3-forced-aligner-0.6b/v2026.05.1" \
             "${MODELS_DIR}/pyannote/v3.1" \
             "${MODELS_DIR}/pyannote/segmentation-3.0" \
             "${MODELS_DIR}/pyannote/wespeaker-voxceleb-resnet34-LM" \
             "${MODELS_DIR}/cam-plus/v1"

    # We use a single manifest-driven downloader that retries transient
    # failures, skips already-complete snapshots, resumes existing files
    # through each hub client, and verifies that every registered model has
    # the minimum local files the runtime/offline packaging needs.
    uv run --extra real-models python - <<'PY'
from __future__ import annotations

import dataclasses
import inspect
import json
import os
import pathlib
import random
import sys
import time
from collections.abc import Callable, Iterable
from typing import Any

root = pathlib.Path(os.environ["MODELS_DIR"]).expanduser().resolve()
hf_token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
max_retries = int(os.environ.get("AI_WORKER_WEIGHTS_MAX_RETRIES", "5"))
workers = int(os.environ.get("AI_WORKER_WEIGHTS_WORKERS", "4"))
force_download = os.environ.get("AI_WORKER_WEIGHTS_FORCE", "0") == "1"
marker_name = ".meeting-download-complete.json"


@dataclasses.dataclass(frozen=True)
class Source:
    provider: str
    repo_id: str
    token_required: bool = False
    revision: str | None = None


@dataclasses.dataclass(frozen=True)
class ModelSpec:
    name: str
    local_dir: pathlib.Path
    sources: tuple[Source, ...]
    required: tuple[tuple[str, ...], ...]
    postprocess: Callable[[], None] | None = None


def _call_supported(fn: Callable[..., Any], /, **kwargs: Any) -> Any:
    params = inspect.signature(fn).parameters
    return fn(**{k: v for k, v in kwargs.items() if k in params and v is not None})


def _matches(directory: pathlib.Path, patterns: Iterable[str]) -> list[pathlib.Path]:
    found: list[pathlib.Path] = []
    for pattern in patterns:
        found.extend(path for path in directory.glob(pattern) if path.is_file())
    return found


def _missing_groups(spec: ModelSpec) -> list[str]:
    missing: list[str] = []
    for group in spec.required:
        if not _matches(spec.local_dir, group):
            missing.append(" or ".join(group))
    return missing


def _marker_path(spec: ModelSpec) -> pathlib.Path:
    return spec.local_dir / marker_name


def _is_complete(spec: ModelSpec) -> bool:
    return spec.local_dir.is_dir() and not _missing_groups(spec)


def _write_marker(spec: ModelSpec, source: Source) -> None:
    payload = {
        "name": spec.name,
        "provider": source.provider,
        "repoId": source.repo_id,
        "revision": source.revision or "main",
        "completedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "required": [list(group) for group in spec.required],
    }
    _marker_path(spec).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def _download_huggingface(source: Source, target: pathlib.Path) -> None:
    if source.token_required and not hf_token:
        raise RuntimeError(
            "HF_TOKEN is required. Accept the pyannote model terms first, then run "
            "HF_TOKEN=hf_... ./deploy/ai-worker-apple-silicon.sh weights"
        )
    from huggingface_hub import snapshot_download

    _call_supported(
        snapshot_download,
        repo_id=source.repo_id,
        revision=source.revision,
        local_dir=str(target),
        local_dir_use_symlinks=False,
        max_workers=workers,
        token=hf_token,
        force_download=force_download,
        resume_download=True,
    )


def _download_modelscope(source: Source, target: pathlib.Path) -> None:
    from modelscope import snapshot_download

    kwargs: dict[str, Any] = {"local_dir": str(target)}
    if source.revision is not None:
        kwargs["revision"] = source.revision
    try:
        snapshot_download(source.repo_id, **kwargs)
    except TypeError:
        _call_supported(
            snapshot_download,
            model_id=source.repo_id,
            revision=source.revision,
            local_dir=str(target),
        )


def _download_once(source: Source, target: pathlib.Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    if source.provider == "hf":
        _download_huggingface(source, target)
        return
    if source.provider == "modelscope":
        _download_modelscope(source, target)
        return
    raise RuntimeError(f"unsupported provider: {source.provider}")


def _download_with_retry(spec: ModelSpec) -> None:
    if _is_complete(spec) and not force_download:
        if not _marker_path(spec).is_file():
            _write_marker(spec, Source("existing", "local-files"))
        print(f"✓ {spec.name}: complete snapshot already exists, skipping")
        return

    last_error: BaseException | None = None
    for attempt in range(1, max_retries + 1):
        for source in spec.sources:
            try:
                print(
                    f"▸ {spec.name}: downloading from {source.provider}:{source.repo_id} "
                    f"(attempt {attempt}/{max_retries})"
                )
                _download_once(source, spec.local_dir)
                if spec.postprocess is not None:
                    spec.postprocess()
                missing = _missing_groups(spec)
                if missing:
                    raise RuntimeError(
                        f"download finished but required files are missing: {missing}"
                    )
                _write_marker(spec, source)
                print(f"✓ {spec.name}: ready at {spec.local_dir}")
                return
            except BaseException as exc:
                last_error = exc
                print(f"⚠ {spec.name}: {source.provider}:{source.repo_id} failed: {exc}", file=sys.stderr)
        if attempt < max_retries:
            delay = min(60.0, 2.0 ** (attempt - 1)) + random.uniform(0.0, 1.5)
            print(f"▸ {spec.name}: retrying in {delay:.1f}s", file=sys.stderr)
            time.sleep(delay)
    raise RuntimeError(f"{spec.name} failed after {max_retries} attempts: {last_error}")


def _patch_pyannote_config() -> None:
    config = root / "pyannote/v3.1/config.yaml"
    if not config.is_file():
        return
    backup = config.with_name("config.upstream.yaml")
    text = config.read_text()
    if force_download or not backup.exists():
        backup.write_text(text)
        source_text = text
    else:
        source_text = backup.read_text()

    replacements = {
        "pyannote/segmentation-3.0": str(root / "pyannote/segmentation-3.0"),
        "pyannote/wespeaker-voxceleb-resnet34-LM": str(
            root / "pyannote/wespeaker-voxceleb-resnet34-LM"
        ),
    }
    patched = source_text
    for remote_id, local_path in replacements.items():
        patched = patched.replace(remote_id, local_path)
    if patched != text:
        config.write_text(patched)
        print("✓ pyannote-diarization: patched config.yaml to local submodel paths")


specs = (
    ModelSpec(
        name="bge-m3",
        local_dir=root / "bge-m3/v1",
        sources=(
            Source("modelscope", "BAAI/bge-m3"),
            Source("hf", "BAAI/bge-m3"),
        ),
        required=(
            ("config.json",),
            ("tokenizer_config.json",),
            ("tokenizer.json", "sentencepiece.bpe.model", "vocab.txt"),
            ("colbert_linear.pt",),
            ("sparse_linear.pt",),
            ("model.safetensors", "model-*.safetensors", "pytorch_model.bin"),
        ),
    ),
    ModelSpec(
        name="bge-reranker-v2-m3",
        local_dir=root / "bge-reranker-v2-m3/v1",
        sources=(
            Source("modelscope", "AI-ModelScope/bge-reranker-v2-m3"),
            Source("hf", "BAAI/bge-reranker-v2-m3"),
        ),
        required=(
            ("config.json",),
            ("tokenizer_config.json",),
            ("tokenizer.json", "sentencepiece.bpe.model", "vocab.txt"),
            ("model.safetensors", "model-*.safetensors", "pytorch_model.bin"),
        ),
    ),
    ModelSpec(
        name="qwen3-asr-1.7b",
        local_dir=root / "qwen3-asr-1.7b/v2026.05.1",
        sources=(
            Source("modelscope", "Qwen/Qwen3-ASR-1.7B"),
            Source("hf", "Qwen/Qwen3-ASR-1.7B"),
        ),
        required=(
            ("config.json",),
            ("preprocessor_config.json",),
            ("tokenizer_config.json",),
            ("vocab.json",),
            ("merges.txt",),
            ("model.safetensors", "model-*.safetensors"),
        ),
    ),
    ModelSpec(
        name="qwen3-forced-aligner-0.6b",
        local_dir=root / "qwen3-forced-aligner-0.6b/v2026.05.1",
        sources=(
            Source("modelscope", "Qwen/Qwen3-ForcedAligner-0.6B"),
            Source("hf", "Qwen/Qwen3-ForcedAligner-0.6B"),
        ),
        required=(
            ("config.json",),
            ("preprocessor_config.json",),
            ("tokenizer_config.json",),
            ("vocab.json",),
            ("merges.txt",),
            ("model.safetensors", "model-*.safetensors"),
        ),
    ),
    ModelSpec(
        name="pyannote-diarization",
        local_dir=root / "pyannote/v3.1",
        sources=(Source("hf", "pyannote/speaker-diarization-3.1", token_required=True),),
        required=(("config.yaml",),),
        postprocess=_patch_pyannote_config,
    ),
    ModelSpec(
        name="pyannote-segmentation-3.0",
        local_dir=root / "pyannote/segmentation-3.0",
        sources=(Source("hf", "pyannote/segmentation-3.0", token_required=True),),
        required=(("config.yaml",), ("pytorch_model.bin", "model.safetensors", "*.ckpt")),
    ),
    ModelSpec(
        name="pyannote-wespeaker-voxceleb-resnet34-LM",
        local_dir=root / "pyannote/wespeaker-voxceleb-resnet34-LM",
        sources=(Source("hf", "pyannote/wespeaker-voxceleb-resnet34-LM", token_required=True),),
        required=(("config.yaml",), ("pytorch_model.bin", "model.safetensors", "*.bin")),
    ),
    ModelSpec(
        name="3d-speaker-cam-plus",
        local_dir=root / "cam-plus/v1",
        sources=(
            Source("modelscope", "iic/speech_campplus_sv_zh-cn_16k-common"),
            Source("modelscope", "damo/speech_campplus_sv_zh-cn_16k-common"),
        ),
        required=(("configuration.json", "config.yaml", "config.json"), ("*.pt", "*.pth", "*.bin", "*.onnx")),
    ),
)

print("==========================================================")
print(f"▸ Download root: {root}")
print("▸ Registered model snapshots:")
for spec in specs:
    print(f"  - {spec.name}: {spec.local_dir.relative_to(root)}")
print("==========================================================")

if not hf_token:
    print("⚠ HF_TOKEN is not set. Already-complete pyannote snapshots will be skipped,")
    print("⚠ but missing gated pyannote files cannot be downloaded without a token.")

try:
    for spec in specs:
        _download_with_retry(spec)
    _patch_pyannote_config()
except BaseException as exc:
    print(f"✗ Weight download failed: {exc}", file=sys.stderr)
    sys.exit(1)

print("==========================================================")
print("✓ All registered real weights are present and verified.")
print("==========================================================")
PY
}

ensure_env_file() {
    local env_file="${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env"
    if [ ! -f "${env_file}" ]; then
        cat > "${env_file}" <<EOF
# Auto-generated on $(date -u +%FT%TZ) — edit freely, this script will
# not overwrite an existing file.
AI_WORKER_RABBITMQ_HOST=localhost
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=meeting_dev
AI_WORKER_MEETING_API_BASE_URL=http://localhost:8080
AI_WORKER_JAVA_API_BASE_URL=http://localhost:8080
AI_WORKER_CALLBACK_HMAC_SECRET=$(openssl rand -hex 32)
AI_WORKER_INTERNAL_API_HMAC_SECRET=$(openssl rand -hex 32)
AI_WORKER_ADMIN_JWT_SECRET=$(openssl rand -hex 32)
EOF
        log "Wrote ${env_file} with fresh HMAC secrets — copy these into meeting-api too."
    else
        log "Using existing ${env_file}"
    fi
}

run_api() {
    log "Installing all real-model extras (FlagEmbedding + funasr + pyannote.audio)"
    cd "${AI_WORKER_DIR}"
    uv sync --extra dev --extra real-models

    ensure_env_file
    local env_file="${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env"
    set -a
    # shellcheck disable=SC1090
    . "${env_file}"
    set +a

    # ── Apple Silicon device split (real models, no CUDA) ───────────
    export AI_WORKER_USE_FAKE_RUNTIME=false
    export AI_WORKER_USE_FAKE_ASR_RUNTIME=false
    export AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false
    # The "weights" step downloads the CAM++ speaker model, so run it real too
    # (otherwise this "full real-models path" silently keeps speaker on fake).
    export AI_WORKER_USE_FAKE_SPEAKER_RUNTIME=false
    export AI_WORKER_BGE_M3_DEVICE=mps
    export AI_WORKER_BGE_RERANKER_DEVICE=mps
    export AI_WORKER_BGE_M3_DTYPE=fp32
    export AI_WORKER_BGE_RERANKER_DTYPE=fp32
    export AI_WORKER_ASR_DEVICE=cpu
    export AI_WORKER_DIARIZATION_DEVICE=cpu
    export AI_WORKER_SPEAKER_DEVICE=cpu

    # Pin model dirs to the staged location (version subdirs already
    # created by stage/weights step).
    export AI_WORKER_BGE_M3_MODELS_DIR="${MODELS_DIR}/bge-m3/v1"
    export AI_WORKER_BGE_RERANKER_MODELS_DIR="${MODELS_DIR}/bge-reranker-v2-m3/v1"
    export AI_WORKER_QWEN3_ASR_MODELS_DIR="${MODELS_DIR}/qwen3-asr-1.7b/v2026.05.1"
    export AI_WORKER_PYANNOTE_MODELS_DIR="${MODELS_DIR}/pyannote/v3.1"
    export AI_WORKER_CAM_PLUS_MODELS_DIR="${MODELS_DIR}/cam-plus/v1"

    # Force offline mode AFTER the weights step has finished; if a user
    # hits `run` without downloading first, drop offline so HF can lazy-
    # pull. Toggle by setting AI_WORKER_OFFLINE=1 explicitly.
    if [ "${AI_WORKER_OFFLINE:-0}" = "1" ]; then
        export HF_HUB_OFFLINE=1
        export TRANSFORMERS_OFFLINE=1
    fi

    log "Starting ai-worker — listening on :8090. Ctrl-C to stop."
    log "Expected throughput on M-series: ASR ≈ 0.5× realtime, diarization ≈ 1× realtime."
    exec uv run ai-worker-api
}

verify_api() {
    log "Polling /internal/hardware + /internal/ready (ai-worker must be running)"
    curl -fsSL http://localhost:8090/internal/hardware | python3 -m json.tool
    echo
    curl -fsSL http://localhost:8090/internal/ready | python3 -m json.tool
}

main() {
    require_apple_silicon
    ensure_deps
    case "${1:-}" in
        stage)   stage_mock_weights ;;
        weights) MODELS_DIR="${MODELS_DIR}" download_real_weights ;;
        env)     ensure_env_file ;;
        run)     run_api ;;
        verify)  verify_api ;;
        *)
            cat <<EOF
Usage: $0 {stage|weights|env|run|verify}

  stage     Stage deterministic mock weights into \${MODELS_DIR}
            (default: \${HOME}/meeting-models). Use for offline smoke.
  weights   Download REAL weights from HuggingFace / ModelScope into
            \${MODELS_DIR}. Includes BGE, Qwen3-ASR, Qwen3-ForcedAligner,
            pyannote submodels, and CAM++ speaker embedding weights.
            HF_TOKEN is required for gated pyannote models.
  env       Create/reuse deploy/.ai-worker-apple-silicon.env with local
            RabbitMQ / meeting-api URLs and HMAC secrets.
  run       uv sync --extra real-models, then start ai-worker-api on
            :8090 with MPS for BGE / CPU for ASR + diarization.
  verify    curl /internal/hardware and /internal/ready against a
            running ai-worker.

Env knobs:
  AI_WORKER_MODELS_ROOT  Override the weights root (default: ~/meeting-models)
  HF_TOKEN               HuggingFace token (needed for pyannote)
  AI_WORKER_OFFLINE=1    Force HF_HUB_OFFLINE + TRANSFORMERS_OFFLINE at run time
  AI_WORKER_WEIGHTS_MAX_RETRIES  Download retry count (default: 5)
  AI_WORKER_WEIGHTS_WORKERS      HuggingFace parallel workers (default: 4)
  AI_WORKER_WEIGHTS_FORCE=1      Refresh snapshots even if complete markers exist
  AI_WORKER_SKIP_UV_SYNC=1       Reuse current uv env before weights download
EOF
            exit 64
            ;;
    esac
}

main "$@"
