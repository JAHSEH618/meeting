# syntax=docker/dockerfile:1.6
#
# ai-worker multi-stage build (Phase 9 P6 — todo-final.md E1.1):
#   1. web-build  — builds the workstation SPA (apps/ai-worker-web/dist/)
#   2. runtime    — CUDA + Python 3.11 + uv + the FastAPI app, COPYing the
#                   SPA bundle into /app/admin-ui/ so the FastAPI static-files
#                   mount serves it at /workstation/*.
#
# The build context expected for this Dockerfile is the REPO ROOT (so it
# can see both apps/ai-worker and apps/ai-worker-web). Compose/k8s build
# pipelines must run with `context: ../../..` relative to apps/ai-worker.

# ----- web build stage -------------------------------------------------------
FROM node:20-alpine AS web-build
WORKDIR /web
COPY apps/ai-worker-web/package.json apps/ai-worker-web/package-lock.json* ./
RUN npm ci --ignore-scripts
COPY apps/ai-worker-web/ ./
RUN npm run build && ls -la dist

# ----- runtime stage ---------------------------------------------------------
# Base image notes (Phase J ML hardening):
#   * Default base is ``nvidia/cuda:13.0-cudnn-runtime-ubuntu22.04``. We stay
#     on Ubuntu 22.04 (jammy) because the apt step below installs
#     ``python3.11``, which is in jammy's standard repo but NOT in noble's
#     (Ubuntu 24.04 ships python3.12 by default). CUDA 13 supports both
#     22.04 and 24.04 — see the NVIDIA install guide.
#   * Override at build time with ``--build-arg BASE=...`` to switch wheels;
#     CPU-only dev boxes use ``BASE=python:3.11-slim``.
#
# ``UV_EXTRAS`` controls which optional extras are installed in the image:
#   <empty>           — runtime + workstation BFF only (fake ML mode)
#   real-bge          — embedding + reranker box
#   real-asr          — Qwen3-ASR box
#   real-diarization  — pyannote diarization box
#   real-models       — single-host all-GPU box (everything)
# Multiple extras can be passed comma-separated, e.g. `real-bge,real-asr`.
ARG BASE=nvidia/cuda:13.0.0-cudnn-runtime-ubuntu22.04
ARG UV_EXTRAS=""
FROM ${BASE}
ARG UV_EXTRAS

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    # Phase 8.4.3.a — production builds must never hit hf.co. The base
    # image flag is the default; per-deployment overrides should set it
    # to "0" only in air-gapped staging when a mirror is configured.
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    # Phase 9 — points FastAPI's StaticFiles mount at the COPYed dist.
    AI_WORKER_ADMIN_UI_DIST_PATH=/app/admin-ui

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
         python3.11 python3.11-venv python3-pip \
         curl ca-certificates \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/bin/python3.11 /usr/local/bin/python

# uv is the documented dependency manager; install it pinned.
RUN curl -LsSf https://astral.sh/uv/install.sh | sh \
    && ln -s /root/.local/bin/uv /usr/local/bin/uv

WORKDIR /app

# Cache deps separately from source. uv sync --frozen requires uv.lock
# to be present alongside pyproject.toml — the dev workflow keeps both
# checked in.
COPY apps/ai-worker/pyproject.toml ./
COPY apps/ai-worker/uv.lock* ./
# Translate the comma-separated UV_EXTRAS build arg into uv's `--extra` flags
# so a CUDA box can opt into real-models, real-bge, etc. without changing
# the Dockerfile. Empty UV_EXTRAS keeps the lean default (fake runtime).
# POSIX-only loop — works on the base sh in nvidia/cuda + python:3.11-slim.
#
# Frozen vs re-resolve policy: any real-* extra installs heavy ML deps
# whose CUDA-wheel ↔ torch ↔ CUDA-runtime relationship is brittle —
# silently re-resolving when the lock drifts has produced incompatible
# wheel sets in past rollouts. So:
#   * UV_EXTRAS empty (lean dev/CPU image) → soft fallback retained so a
#     CLAUDE.md tweak doesn't block dev image rebuilds on lock churn.
#   * UV_EXTRAS set → frozen-only; mismatch fails the build so the lock
#     gets regenerated locally before a tagged production image ships.
RUN extras="" \
    && for p in $(echo "$UV_EXTRAS" | tr ',' ' '); do extras="$extras --extra $p"; done \
    && if [ -n "$UV_EXTRAS" ]; then \
        uv sync --frozen --no-dev $extras; \
       else \
        uv sync --frozen --no-dev $extras || uv sync --no-dev $extras; \
       fi

COPY apps/ai-worker/ai_worker ./ai_worker
COPY --from=web-build /web/dist /app/admin-ui

# Non-root user; the model weights mount is expected to be readable
# by uid 1001 (set via the StatefulSet's fsGroup).
RUN useradd --uid 1001 --create-home --shell /bin/false ai-worker \
    && mkdir -p /opt/models /app/.artifacts /var/lib/ai-worker/enrollment \
    && chown -R ai-worker:ai-worker /app /opt/models /var/lib/ai-worker
USER ai-worker

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fs http://localhost:8090/internal/health || exit 1

ENTRYPOINT ["uv", "run", "ai-worker-api"]
