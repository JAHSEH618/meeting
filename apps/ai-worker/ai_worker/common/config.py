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
    # Device autodetect happens in the runtime; "auto" → cuda > mps > cpu.
    model_device: str = "auto"
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")


settings = Settings()
