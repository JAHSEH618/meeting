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
#                                               # (BGE + pyannote via HF,
#                                               #  Qwen3-ASR via funasr
#                                               #  hub auto-download)
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
    log "Downloading real weights into ${MODELS_DIR}"
    mkdir -p "${MODELS_DIR}/bge-m3/v1" \
             "${MODELS_DIR}/bge-reranker-v2-m3/v1" \
             "${MODELS_DIR}/qwen3-asr-1.7b/v2026.05.1" \
             "${MODELS_DIR}/pyannote/v3.1"

    # BGE-m3 + BGE-reranker — public on HuggingFace, no token required.
    cd "${AI_WORKER_DIR}"
    uv run --extra real-bge python - <<'PY'
from huggingface_hub import snapshot_download
import os, pathlib
root = pathlib.Path(os.environ["MODELS_DIR"])
snapshot_download("BAAI/bge-m3", local_dir=str(root / "bge-m3/v1"),
                  local_dir_use_symlinks=False, max_workers=4)
snapshot_download("BAAI/bge-reranker-v2-m3", local_dir=str(root / "bge-reranker-v2-m3/v1"),
                  local_dir_use_symlinks=False, max_workers=4)
PY

    # pyannote/speaker-diarization-3.1 — gated repo; user must have
    # accepted the terms and exported HF_TOKEN. The model README is at
    # https://huggingface.co/pyannote/speaker-diarization-3.1
    if [ -z "${HF_TOKEN:-}" ]; then
        warn "HF_TOKEN not set — skipping pyannote download. Accept the terms at"
        warn "  https://huggingface.co/pyannote/speaker-diarization-3.1"
        warn "then re-run: HF_TOKEN=hf_... ./deploy/ai-worker-apple-silicon.sh weights"
    else
        uv run --extra real-diarization python - <<'PY'
from huggingface_hub import snapshot_download
import os, pathlib
root = pathlib.Path(os.environ["MODELS_DIR"])
snapshot_download("pyannote/speaker-diarization-3.1",
                  local_dir=str(root / "pyannote/v3.1"),
                  local_dir_use_symlinks=False, max_workers=4,
                  token=os.environ["HF_TOKEN"])
PY
    fi

    # Qwen3-ASR — pulled lazily by funasr on first inference; we just
    # pre-create the version dir so the checksum guard does not 503.
    warn "Qwen3-ASR weights are fetched by funasr on first call. To pre-download:"
    warn "  uv run --extra real-asr python -c \"from funasr import AutoModel; AutoModel(model='paraformer-zh', cache_dir='${MODELS_DIR}/qwen3-asr-1.7b/v2026.05.1')\""
}

run_api() {
    log "Installing all real-model extras (FlagEmbedding + funasr + pyannote.audio)"
    cd "${AI_WORKER_DIR}"
    uv sync --extra dev --extra real-models

    # Generate HMAC secrets if the env file doesn't already declare them.
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
    fi
    set -a
    # shellcheck disable=SC1090
    . "${env_file}"
    set +a

    # ── Apple Silicon device split (real models, no CUDA) ───────────
    export AI_WORKER_USE_FAKE_RUNTIME=false
    export AI_WORKER_USE_FAKE_ASR_RUNTIME=false
    export AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false
    export AI_WORKER_BGE_M3_DEVICE=mps
    export AI_WORKER_BGE_RERANKER_DEVICE=mps
    export AI_WORKER_BGE_M3_DTYPE=fp32
    export AI_WORKER_BGE_RERANKER_DTYPE=fp32
    export AI_WORKER_ASR_DEVICE=cpu
    export AI_WORKER_DIARIZATION_DEVICE=cpu

    # Pin model dirs to the staged location (version subdirs already
    # created by stage/weights step).
    export AI_WORKER_BGE_M3_MODELS_DIR="${MODELS_DIR}/bge-m3/v1"
    export AI_WORKER_BGE_RERANKER_MODELS_DIR="${MODELS_DIR}/bge-reranker-v2-m3/v1"
    export AI_WORKER_QWEN3_ASR_MODELS_DIR="${MODELS_DIR}/qwen3-asr-1.7b/v2026.05.1"
    export AI_WORKER_PYANNOTE_MODELS_DIR="${MODELS_DIR}/pyannote/v3.1"

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
        run)     run_api ;;
        verify)  verify_api ;;
        *)
            cat <<EOF
Usage: $0 {stage|weights|run|verify}

  stage     Stage deterministic mock weights into \${MODELS_DIR}
            (default: \${HOME}/meeting-models). Use for offline smoke.
  weights   Download REAL weights from HuggingFace into \${MODELS_DIR}.
            HF_TOKEN required for pyannote/speaker-diarization-3.1.
  run       uv sync --extra real-models, then start ai-worker-api on
            :8090 with MPS for BGE / CPU for ASR + diarization.
  verify    curl /internal/hardware and /internal/ready against a
            running ai-worker.

Env knobs:
  AI_WORKER_MODELS_ROOT  Override the weights root (default: ~/meeting-models)
  HF_TOKEN               HuggingFace token (needed for pyannote)
  AI_WORKER_OFFLINE=1    Force HF_HUB_OFFLINE + TRANSFORMERS_OFFLINE at run time
EOF
            exit 64
            ;;
    esac
}

main "$@"
