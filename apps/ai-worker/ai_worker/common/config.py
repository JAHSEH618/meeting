from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_id: str = "worker_dev_001"
    meeting_api_base_url: str = "http://localhost:8080"
    callback_hmac_secret: str = "dev-secret"
    internal_api_hmac_secret: str = "dev-internal-secret"
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "meeting"
    rabbitmq_password: str = "meeting_dev"
    rabbitmq_virtual_host: str = "/"
    rabbitmq_task_queues: str = "audio-cpu-queue,gpu-asr-queue,gpu-diar-queue,gpu-speaker-queue,embed-queue"
    callback_max_retries: int = 3
    artifact_store_root: str = ".artifacts"
    # ── Phase 5 RAG model runtime ──────────────────────────────────────────
    # Default true: coding / CI / single dev tests run without downloading
    # bge-m3 (~3GB) or bge-reranker-v2-m3 (~568MB). Production deploys must
    # explicitly set AI_WORKER_USE_FAKE_RUNTIME=false; see docs/model-registry.md.
    use_fake_runtime: bool = True
    # Optional local snapshot dirs. When unset, real-mode falls back to
    # HuggingFace Hub (`BAAI/bge-m3`, `BAAI/bge-reranker-v2-m3`). Production
    # must set these to the internal artifact mount and HF_HUB_OFFLINE=1.
    bge_m3_models_dir: str | None = None
    bge_reranker_models_dir: str | None = None
    # ── final-check.md A1 — real ASR / diarization runtime ──────────────────
    # Pipeline runtimes are independent of use_fake_runtime so we can ship
    # bge-m3 / bge-reranker live while keeping ASR / diarization on the
    # deterministic fallback until weights are staged. Production must point
    # each *_MODELS_DIR at the internal artifact mount and run with
    # HF_HUB_OFFLINE=1 + TRANSFORMERS_OFFLINE=1 (already set in the Dockerfile).
    use_fake_asr_runtime: bool = True
    use_fake_diarization_runtime: bool = True
    qwen3_asr_models_dir: str | None = None
    pyannote_models_dir: str | None = None
    # Device autodetect happens in the runtime; "auto" → cuda > mps > cpu.
    model_device: str = "auto"
    # ── Phase 9 workstation BFF (P3) ──────────────────────────────────────
    # Workstation admin UI is hosted same-process under /admin/*. The BFF
    # validates JWTs minted by meeting-api and proxies to Java public API.
    # Production must set:
    #   AI_WORKER_JAVA_API_BASE_URL=https://meeting-api.internal
    #   AI_WORKER_ADMIN_JWT_SECRET=<32+ random bytes shared with Java>
    # JWKS path is reserved for the asymmetric-key migration (see docs).
    java_api_base_url: str | None = None
    admin_jwt_secret: str = "dev-admin-secret"
    admin_jwt_audience: str = "ai-worker-admin"
    admin_jwt_issuer: str = "meeting-api"
    admin_jwt_required_role: str = "ADMIN"
    enrollment_tmp_dir: str = "/tmp/ai-worker-admin/enrollment"
    admin_session_ttl_seconds: int = 24 * 60 * 60
    admin_session_cleanup_interval_seconds: int = 5 * 60
    admin_ui_dist_path: str | None = None
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")


settings = Settings()
