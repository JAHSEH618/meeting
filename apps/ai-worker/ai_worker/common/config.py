from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    env: str = "dev"
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
    # ── Storage backend (TOS read-path) ───────────────────────────────────
    # storage_backend selects how ai-worker resolves ``tos://bucket/key`` URIs
    # produced by meeting-api. Choices:
    #   "local" — default for dev / CI: read & write under artifact_store_root
    #             on local disk. Audio uploaded directly via this gateway
    #             must already exist on the worker's local filesystem (e.g.
    #             docker volume mounted from meeting-api's local-root).
    #   "tos"   — production: download audio + reference artifacts directly
    #             from Volcano Engine TOS using a worker-only **read-only** RAM
    #             credential (tos:GetObject + tos:HeadObject only). Writes
    #             (artifact uploads such as quality-report JSON) still go to
    #             local emptyDir today — Java is the single writer of
    #             business-relevant objects. See TosArtifactStore docstring.
    storage_backend: str = "local"
    tos_endpoint: str | None = None
    tos_region: str | None = None
    tos_access_key_id: str | None = None
    tos_access_key_secret: str | None = None
    enable_tos_backup: bool = True
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
    # ``model_device`` is the legacy global default; the per-model env vars
    # below win when set so deployments can pin e.g. ASR to ``cuda:0`` while
    # leaving embedding on CPU. Each defaults to ``auto`` so existing single-
    # GPU / single-MPS boxes don't change behaviour.
    model_device: str = "auto"
    bge_m3_device: str = "auto"
    bge_reranker_device: str = "auto"
    asr_device: str = "auto"
    diarization_device: str = "auto"
    # Optional explicit dtype override. ``auto`` resolves per-device:
    #   CUDA → fp16   (matches FlagEmbedding/funasr defaults)
    #   MPS  → fp32   (fp16 is unstable on many ops, see PyTorch MPS notes)
    #   CPU  → fp32
    # Allowed values: ``auto`` / ``fp16`` / ``fp32``. bf16 is NOT supported
    # because FlagEmbedding's ``use_fp16`` flag is the only knob we expose
    # — accepting bf16 and mapping it to fp16 would be misleading. Add a
    # real dtype path before re-introducing the value.
    bge_m3_dtype: str = "auto"
    bge_reranker_dtype: str = "auto"
    # ── Phase J — checksum guard (J4) ───────────────────────────────────
    # When set, /internal/models compares the live compute_checksum() of
    # the corresponding *_models_dir against the expected hash and forces
    # ``status=ERROR`` + ``lastError`` on mismatch, regardless of the
    # runtime's own loading state. Leave unset to disable the guard for
    # that model (default for dev / CI / fake mode).
    #
    # Format: full ``compute_checksum()`` output, i.e. ``sha256:<hex>``.
    bge_m3_expected_checksum: str | None = None
    bge_reranker_expected_checksum: str | None = None
    qwen3_asr_expected_checksum: str | None = None
    pyannote_expected_checksum: str | None = None
    # ── Phase 9 workstation BFF (P3) ──────────────────────────────────────
    # Workstation admin UI is hosted same-process under /admin/*. The BFF
    # validates JWTs minted by meeting-api and proxies to Java public API.
    # Production must set:
    #   AI_WORKER_JAVA_API_BASE_URL=https://meeting-api.internal
    #   AI_WORKER_ADMIN_JWT_SECRET=<32+ random bytes shared with Java>
    # JWKS path is reserved for the asymmetric-key migration (see docs).
    java_api_base_url: str | None = None
    admin_jwt_secret: str = "dev-admin-secret-32-bytes-fixedXX"
    admin_jwt_audience: str = "ai-worker-admin"
    admin_jwt_issuer: str = "meeting-api"
    admin_jwt_required_role: str = "ADMIN"
    enrollment_tmp_dir: str = "/tmp/ai-worker-admin/enrollment"
    admin_session_ttl_seconds: int = 24 * 60 * 60
    admin_session_cleanup_interval_seconds: int = 5 * 60
    admin_ui_dist_path: str | None = None
    # Workstation runtime config — served at GET /workstation/runtime-config.json
    # so the SPA can read window.__WORKSTATION_CONFIG__ at runtime and we can
    # flip the Java login URL per environment without rebuilding the image.
    # ``None`` falls through to the SPA's build-time setting. If neither is
    # configured, the SPA uses its own /workstation/login page and posts to
    # Java /api/auth/login through the same-origin /api path.
    auth_login_url: str | None = None
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")


settings = Settings()
