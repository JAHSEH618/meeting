from typing import Literal

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_id: str = "worker_dev_001"
    meeting_api_base_url: str = "http://localhost:8080"
    callback_hmac_secret: str = "dev-secret"
    callback_heartbeat_interval_seconds: float = 15.0
    internal_api_hmac_secret: str = "dev-internal-secret"
    # Replay-nonce store. Unset (default) → per-process in-memory TTL cache,
    # fine for single-replica dev/CI. Multi-replica prod MUST set this to a
    # shared Redis (e.g. "redis://nonce-redis:6379/0") so a replay aimed at a
    # different pod is still caught. On a Redis outage the check degrades to the
    # in-memory cache rather than failing internal calls.
    nonce_redis_url: str | None = None
    nonce_redis_key_prefix: str = "ai-worker:nonce:"
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "meeting"
    rabbitmq_password: str = "meeting_dev"
    rabbitmq_virtual_host: str = "/"
    rabbitmq_task_queues: str = "audio-cpu-queue,gpu-asr-queue,gpu-diar-queue,gpu-speaker-queue,embed-queue"
    # Max unacked messages the broker delivers concurrently. Each in-flight
    # message runs as a coroutine on the consumer's worker event loop while the
    # pika I/O thread stays free to send AMQP heartbeats. Default 1 preserves
    # at-most-one-in-flight behaviour; raise it to pipeline CPU (embed) work
    # behind long GPU (ASR/diar) work. Per-device semaphores still gate GPU
    # concurrency, so raising this never over-subscribes the GPU.
    rabbitmq_prefetch_count: int = 1
    callback_max_retries: int = 3
    # Result-writeback callbacks (step transitions, transcript, embeddings,
    # speaker candidates, complete, fail) carry work that is expensive to
    # recompute, so instead of the attempt-count retry above they keep
    # retrying transport failures with capped exponential backoff for up to
    # this many seconds. Sized to ride out an ordinary meeting-api rolling
    # restart (10-30s unavailable) while staying comfortably below the Java
    # lease TTL (120s) so a genuinely dead API still falls back to
    # lease-expiry requeue. Heartbeats are excluded: the next one supersedes.
    callback_writeback_retry_budget_seconds: float = 90.0
    callback_retry_max_backoff_seconds: float = 10.0
    artifact_store_root: str = ".artifacts"
    # ── Model deployment profile controls ───────────────────────────────
    # These settings are intentionally lightweight in Stage 1: they give
    # K8s/Mac deployments a typed contract before profile-specific workers
    # are split out. Later stages can turn the profile into stricter startup
    # validation without changing the environment variable names again.
    model_profile: str = "all"  # api, bge, asr, diar, speaker, all
    local_profile: str | None = None  # mac-fake, mac-bge, mac-speaker, mac-audio, mac-all
    # Warm the selected model runtimes in the background at process start so
    # the first task / first embed request doesn't pay the multi-minute cold
    # load inside its own latency budget. Honoured by both the API lifespan
    # and the RabbitMQ consumer entrypoint. Comma-separated capability names:
    # embedding, rerank, asr, diarization, speaker.
    model_warmup_on_startup: bool = False
    model_warmup_capabilities: str = "embedding,rerank"
    model_load_timeout_seconds: float = 600.0
    # Per-step timeouts so a hung model load / inference / ffprobe converts into
    # a terminal (retryable) step failure instead of pinning the task RUNNING
    # forever while the heartbeat loop keeps renewing the Java lease.
    # ``model_load_timeout_seconds`` is enforced in every runtime's
    # ``ensure_loaded()``; inference budgets scale with audio duration (a long
    # meeting legitimately runs longer) atop a floor for short clips. ffprobe is
    # a fast metadata probe, so a tight cap is safe.
    asr_inference_timeout_base_seconds: float = 300.0
    asr_inference_timeout_per_audio_minute_seconds: float = 120.0
    diarization_inference_timeout_base_seconds: float = 300.0
    diarization_inference_timeout_per_audio_minute_seconds: float = 120.0
    # Per-turn CAM++ embedding budget: a turn is at most tens of seconds of
    # audio, so a stuck inference is a fault, not a long job.
    speaker_embed_timeout_seconds: float = 120.0
    ffprobe_timeout_seconds: float = 30.0
    # ── Audio normalization (AUDIO_PREPROCESS) ─────────────────────────
    # Transcode every input once to 16 kHz mono PCM WAV so ASR / pyannote /
    # CAM++ all consume the same decoded audio (consistent timeline, no
    # per-model resampling) and compressed uploads (m4a/mp3) stop failing in
    # the soundfile-based speaker path. Disable only if ffmpeg is unavailable.
    audio_normalize_enabled: bool = True
    ffmpeg_transcode_timeout_seconds: float = 600.0
    # Reuse the previous attempt's ASR / diarization result artifacts when a
    # task is retried (e.g. SPEAKER_MATCHING failed on a flaky reference
    # service): the expensive GPU inference is only redone when no matching
    # artifact from an earlier attempt is readable on this host. Artifacts
    # are written to worker-local storage, so reuse applies to same-host
    # retries — a miss silently falls back to full recompute.
    artifact_reuse_enabled: bool = True
    # Run the two independent halves of MEETING_FULL_PIPELINE concurrently:
    # [ASR, ALIGNMENT] and [DIARIZATION, SPEAKER_EMBEDDING, SPEAKER_MATCHING]
    # only join before TRANSCRIPT_MERGE. Per-device semaphores still gate GPU
    # concurrency, so a single-GPU box degrades to near-serial safely, while
    # split-device deployments (asr_device != diarization_device) cut the
    # wall clock by min(T_asr, T_diar).
    pipeline_parallel_branches: bool = True
    bge_m3_batch_size: int = 16
    rerank_batch_size: int = 16
    # Max tokens per (query + passage) pair fed to bge-reranker-v2-m3. The
    # model itself accepts long inputs; 512 silently truncated the tail of a
    # 512-char Chinese chunk, so candidates whose answer sat at the end were
    # systematically under-scored. Keep ≥ chunk size + query budget.
    rerank_max_length: int = 1024
    asr_max_concurrency: int = 1
    diarization_max_concurrency: int = 1
    speaker_max_concurrency: int = 1
    # Give the small interactive models (bge-m3 embed + bge-reranker) their
    # own single GPU slot instead of queuing behind minutes-long ASR/diar
    # inferences — otherwise RAG Q&A times out whenever audio is processing.
    # Costs ~2-3GB extra VRAM alongside the audio models; disable on very
    # tight cards (or pin AI_WORKER_BGE_M3_DEVICE/BGE_RERANKER_DEVICE=cpu).
    gpu_interactive_lane_enabled: bool = True
    # ── ASR segmentation (real runtime) ────────────────────────────────
    # When set, funasr's AutoModel is loaded with this VAD model so long
    # audio is split on silence and transcribed segment-by-segment (with
    # per-segment timestamps) instead of as one monolithic pass. Accepts a
    # model name ("fsmn-vad") or a locally staged path. Offline deployments
    # (HF_HUB_OFFLINE=1) MUST stage the VAD weights and point this at the
    # directory; leave unset to keep single-pass behaviour.
    asr_vad_model: str | None = None
    asr_vad_max_single_segment_ms: int = 30_000
    # Long-audio chunked transcription: split the normalized WAV at silence
    # points near every N-second mark and transcribe piece by piece, so long
    # meetings report real ASR progress (per piece) and never sit in a single
    # unbounded generate() call. Only activates on 16k mono PCM inputs
    # comfortably longer than one chunk; disable to restore single-pass.
    asr_chunked_transcribe_enabled: bool = True
    asr_chunk_target_seconds: float = 300.0
    asr_chunk_search_radius_seconds: float = 45.0
    # Default language funasr receives when the task doesn't carry one.
    # "auto" lets Qwen3-ASR language-detect (better for mixed zh/en meetings).
    asr_default_language: str = "zh"
    # Diarization turns shorter than this are skipped when extracting speaker
    # embeddings — sub-3s clips produce unstable voiceprints that pollute
    # matching. Every speaker keeps at least their longest turn as fallback.
    speaker_min_segment_seconds: float = 3.0
    # Cap how many (longest-first) turns per diarized speaker are embedded and
    # averaged into that speaker's single centroid embedding.
    speaker_max_segments_per_speaker: int = 5
    speaker_top_k: int = 5
    # Cosine-similarity floor a candidate must clear to be reported as a speaker
    # match. Operator-tunable because a model swap (real CAM++ vs deterministic
    # fake) typically needs a different operating point. Wired into
    # AuthorizedScopeMatcher alongside speaker_top_k.
    speaker_min_confidence: float = 0.35
    # Keep source audio downloaded by TosArtifactStore.local_path cached on
    # disk across tasks. Default False: each finished task evicts its cached
    # source audio in cleanup_pipeline — otherwise /tmp/ai-worker-tos grows by
    # one full recording per task until the volume fills. Enable only for
    # debugging/replay workflows on hosts with generous scratch space.
    enable_audio_artifact_cache: bool = False
    model_cache_dir: str | None = None
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
    storage_backend: Literal["local", "tos"] = "local"
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
    use_fake_speaker_runtime: bool = True
    qwen3_asr_models_dir: str | None = None
    pyannote_models_dir: str | None = None
    cam_plus_models_dir: str | None = None
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
    speaker_device: str = "auto"
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
    cam_plus_expected_checksum: str | None = None
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
    # Voiceprint samples are short clips; cap the in-memory upload so a
    # mis-selected full meeting recording can't balloon the worker process.
    enrollment_max_audio_bytes: int = 50 * 1024 * 1024
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
    # Escape hatch for dev / CI / tests so they can run with the shipped default
    # secrets. Production must leave this false; validate_security_config() then
    # hard-fails at startup if any required secret is still its default or too
    # short. Secure-by-default with an explicit, greppable opt-out.
    allow_insecure_secrets: bool = False
    # Escape hatch mirroring allow_insecure_secrets: production signals
    # (storage_backend=tos or a configured *_EXPECTED_CHECKSUM) combined with
    # any use_fake_* flag left true abort startup unless this is explicitly
    # set. Prevents the worst misconfiguration in the system — a "green"
    # deployment silently producing placeholder transcripts.
    allow_fake_runtime: bool = False
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")

    @field_validator("storage_backend", mode="before")
    @classmethod
    def _normalize_storage_backend(cls, value: object) -> object:
        # A typo like "TOS " used to silently fall back to local storage and
        # only surface as AUDIO_OBJECT_NOT_FOUND on the first task. Normalize
        # then let the Literal reject anything unknown at startup.
        if isinstance(value, str):
            return value.strip().lower()
        return value


settings = Settings()


class InsecureConfigError(RuntimeError):
    """Raised at startup when a required secret is still its shipped default."""


_MIN_SECRET_LEN = 32


def validate_security_config(
    settings_obj: "Settings | None" = None,
    *,
    require_admin: bool | None = None,
) -> None:
    """Fail fast if a required secret is still its shipped default or too short.

    Called from the API (`create_app`) and the consumer entrypoint. The admin
    JWT secret is only required when the workstation BFF is enabled
    (`java_api_base_url` set), unless ``require_admin`` overrides the detection.
    A default HMAC secret lets anyone forge internal-API signatures or mint a
    valid admin JWT, so this is a hard stop rather than a warning.
    """
    s = settings_obj or settings
    if s.allow_insecure_secrets:
        return

    problems: list[str] = []

    def _check(field_name: str, value: str, env_name: str) -> None:
        default = Settings.model_fields[field_name].default
        if value == default:
            problems.append(f"{env_name} is still the shipped default")
        elif len(value) < _MIN_SECRET_LEN:
            problems.append(f"{env_name} must be at least {_MIN_SECRET_LEN} bytes")

    _check("internal_api_hmac_secret", s.internal_api_hmac_secret, "AI_WORKER_INTERNAL_API_HMAC_SECRET")
    _check("callback_hmac_secret", s.callback_hmac_secret, "AI_WORKER_CALLBACK_HMAC_SECRET")

    admin_enabled = require_admin if require_admin is not None else bool(s.java_api_base_url)
    if admin_enabled:
        _check("admin_jwt_secret", s.admin_jwt_secret, "AI_WORKER_ADMIN_JWT_SECRET")

    if problems:
        raise InsecureConfigError(
            "refusing to start with insecure secrets: "
            + "; ".join(problems)
            + " (set AI_WORKER_ALLOW_INSECURE_SECRETS=true only for dev/CI/tests)"
        )


class FakeRuntimeConfigError(RuntimeError):
    """Raised at startup when a production-looking deployment still has fake
    model runtimes enabled without an explicit opt-in."""


def validate_runtime_config(settings_obj: "Settings | None" = None) -> None:
    """Fail fast when production signals coexist with fake model runtimes.

    Four independent ``use_fake_*`` flags default to true (right for dev/CI,
    catastrophic in prod): forgetting a single one made the worker report
    READY and return deterministic placeholder transcripts with every task
    SUCCEEDED. We treat ``storage_backend=tos`` or any configured
    ``*_EXPECTED_CHECKSUM`` as "this is production"; from there every fake
    flag must be off unless ``AI_WORKER_ALLOW_FAKE_RUNTIME=true`` explicitly
    acknowledges the mix (e.g. a bge-only worker that intentionally keeps
    ASR fake).
    """
    s = settings_obj or settings
    if s.allow_fake_runtime:
        return

    production_signals = s.storage_backend == "tos" or any(
        checksum
        for checksum in (
            s.bge_m3_expected_checksum,
            s.bge_reranker_expected_checksum,
            s.qwen3_asr_expected_checksum,
            s.pyannote_expected_checksum,
            s.cam_plus_expected_checksum,
        )
    )
    if not production_signals:
        return

    fake_flags = {
        "AI_WORKER_USE_FAKE_RUNTIME": s.use_fake_runtime,
        "AI_WORKER_USE_FAKE_ASR_RUNTIME": s.use_fake_asr_runtime,
        "AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME": s.use_fake_diarization_runtime,
        "AI_WORKER_USE_FAKE_SPEAKER_RUNTIME": s.use_fake_speaker_runtime,
    }
    still_fake = [name for name, value in fake_flags.items() if value]
    if still_fake:
        raise FakeRuntimeConfigError(
            "production storage/checksum config detected but fake model runtimes "
            f"are still enabled: {', '.join(still_fake)} "
            "(set them to false, or set AI_WORKER_ALLOW_FAKE_RUNTIME=true to "
            "explicitly run this mix)"
        )
