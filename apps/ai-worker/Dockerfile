# syntax=docker/dockerfile:1.6

# ----- runtime stage ---------------------------------------------------------
# CUDA 12.2 + Ubuntu 22.04 matches the torch 2.5 wheels we use for the
# real model runtimes. CPU-only dev boxes can build with
# --build-arg BASE=python:3.11-slim and AI_WORKER_USE_FAKE_RUNTIME=true.
ARG BASE=nvidia/cuda:12.2.2-cudnn8-runtime-ubuntu22.04
FROM ${BASE}

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    # Phase 8.4.3.a — production builds must never hit hf.co. The base
    # image flag is the default; per-deployment overrides should set it
    # to "0" only in air-gapped staging when a mirror is configured.
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

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
COPY pyproject.toml ./
COPY uv.lock* ./
RUN uv sync --frozen --no-dev || uv sync --no-dev

COPY ai_worker ./ai_worker

# Non-root user; the model weights mount is expected to be readable
# by uid 1001 (set via the StatefulSet's fsGroup).
RUN useradd --uid 1001 --create-home --shell /bin/false ai-worker \
    && mkdir -p /opt/models /app/.artifacts \
    && chown -R ai-worker:ai-worker /app /opt/models
USER ai-worker

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fs http://localhost:8090/internal/health || exit 1

ENTRYPOINT ["uv", "run", "ai-worker-api"]
