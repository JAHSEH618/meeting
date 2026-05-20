from contextlib import asynccontextmanager
from os.path import isdir
from typing import AsyncIterator

from fastapi import BackgroundTasks, FastAPI, Header, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import settings
from ai_worker.infrastructure.internal_api.auth import (
    EmbedRequest,
    EmbedResponse,
    RerankRequest,
    RerankResponse,
    RerankResultItem,
    verify_hmac_signature,
)
from ai_worker.model_runtime.asr import Qwen3AsrRuntime
from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime
from ai_worker.model_runtime.embedding import BgeM3Runtime, BgeM3RuntimeError
from ai_worker.model_runtime.registry import (
    get_asr_runtime,
    get_bge_m3,
    get_bge_reranker,
    get_diarization_runtime,
)
from ai_worker.model_runtime.rerank import (
    BgeRerankerRuntime,
    BgeRerankerRuntimeError,
)
from ai_worker.observability.gpu_metrics import refresh_gpu_metrics
from ai_worker.observability.model_checksum import compute_checksum


RuntimeLike = (
    BgeM3Runtime | BgeRerankerRuntime | Qwen3AsrRuntime | PyannoteDiarizationRuntime
)


def _error_response(
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    request_id: str,
    trace_id: str,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "success": False,
            "data": None,
            "error": {
                "code": code,
                "message": message,
                "retryable": retryable,
            },
            "requestId": request_id,
            "traceId": trace_id,
        },
    )


def _models_dir_for(runtime: RuntimeLike) -> str | None:
    if isinstance(runtime, BgeM3Runtime):
        return settings.bge_m3_models_dir
    if isinstance(runtime, BgeRerankerRuntime):
        return settings.bge_reranker_models_dir
    if isinstance(runtime, Qwen3AsrRuntime):
        return settings.qwen3_asr_models_dir
    if isinstance(runtime, PyannoteDiarizationRuntime):
        return settings.pyannote_models_dir
    return None


def _expected_checksum_for(runtime: RuntimeLike) -> str | None:
    if isinstance(runtime, BgeM3Runtime):
        return settings.bge_m3_expected_checksum
    if isinstance(runtime, BgeRerankerRuntime):
        return settings.bge_reranker_expected_checksum
    if isinstance(runtime, Qwen3AsrRuntime):
        return settings.qwen3_asr_expected_checksum
    if isinstance(runtime, PyannoteDiarizationRuntime):
        return settings.pyannote_expected_checksum
    return None


def _model_info(runtime: RuntimeLike, name: str) -> dict:
    """Project a runtime into ``ModelInfo`` per ai-worker-internal-api.yaml.

    Two layered concerns:
      1. ``checksum`` is computed lazily from on-disk weight files when
         ``modelsDir`` is set (Phase 8.4.1.b). Falls back to ``None`` for
         fake mode / HF-fallback paths so callers can tell "we don't know"
         from "this is the pinned hash".
      2. Phase J4 checksum guard: when ``AI_WORKER_*_EXPECTED_CHECKSUM``
         is configured, mismatch (or missing weights when the guard is
         armed) forces ``status=ERROR`` with a ``lastError`` describing
         the divergence — without mutating the shared runtime singleton.
         Readiness logic in ``/internal/ready`` rolls these per-model
         statuses up into the probe verdict.
    """
    models_dir = _models_dir_for(runtime)
    actual = compute_checksum(models_dir)
    expected = _expected_checksum_for(runtime)

    status = runtime.status
    last_error = runtime.last_error
    if expected is not None and actual != expected:
        status = "ERROR"
        observed = actual or "<no weights found>"
        last_error = f"checksum mismatch: expected {expected} got {observed}"

    return {
        "name": name,
        "version": runtime.model_version,
        "status": status,
        "device": runtime.device,
        "useFake": runtime.use_fake,
        "checksum": actual,
        "modelsDir": models_dir,
        "lastError": last_error,
    }


def _all_model_infos() -> list[dict]:
    return [
        _model_info(get_bge_m3(), "bge-m3"),
        _model_info(get_bge_reranker(), "bge-reranker-v2-m3"),
        _model_info(get_asr_runtime(), "qwen3-asr"),
        _model_info(get_diarization_runtime(), "pyannote-diarization"),
    ]


async def _safe_ensure_loaded(runtime: BgeM3Runtime | BgeRerankerRuntime) -> None:
    """Background-task wrapper that swallows load failures.

    Errors are surfaced via ``runtime.status == "ERROR"`` and ``last_error``
    so the next request can return a 503 with full context — we just don't
    want an unhandled exception to escape the background task.
    """
    try:
        await runtime.ensure_loaded()
    except Exception:
        pass


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Phase J — replaces deprecated ``@app.on_event``.

    The enrollment session cleanup loop is only wired when the workstation
    BFF is enabled (``AI_WORKER_JAVA_API_BASE_URL`` set); otherwise the
    admin module isn't mounted and starting a cleanup task would be dead
    weight.
    """
    cleanup_started = False
    if settings.java_api_base_url:
        from ai_worker.admin.session_store import enrollment_session_store

        await enrollment_session_store.start_cleanup_loop()
        cleanup_started = True
    try:
        yield
    finally:
        if cleanup_started:
            from ai_worker.admin.session_store import enrollment_session_store

            await enrollment_session_store.stop_cleanup_loop()


def _mount_admin_router(app: FastAPI) -> None:
    """Phase 9 workstation BFF — mounted only when ``java_api_base_url`` is set.

    Tests for the admin module construct their own router via
    :func:`ai_worker.admin.build_admin_router` with a mocked Java client;
    in production this is invoked from :func:`create_app` when the env
    is wired. Lifecycle hooks live in :func:`lifespan` (Phase J).
    """
    if not settings.java_api_base_url:
        return
    from ai_worker.admin import build_admin_router

    app.include_router(build_admin_router())


def _mount_admin_ui(app: FastAPI) -> None:
    """Phase 9 P6 / E1.2 — mount the workstation SPA at ``/workstation/`` when
    a build artefact dir is configured. Kept separate from ``/admin/*`` so the
    BFF routes don't collide with the static file handler.

    Uses a small ``StaticFiles`` subclass that falls back to ``index.html``
    only for SPA-route 404s (no file extension, not under ``assets/``).
    Asset 404s are intentionally left as real 404s so the browser surfaces
    a clear network error instead of silently parsing ``index.html`` as a
    JS module / stylesheet (Phase J UX hardening).

    Also exposes ``GET /workstation/runtime-config.js`` so the SPA can read
    ``window.__WORKSTATION_CONFIG__`` and avoid rebuilding the image when an
    environment-specific URL (e.g. Java login) changes. The explicit route
    must be registered BEFORE the mount or the static handler shadows it.
    """
    if not settings.admin_ui_dist_path:
        return
    if not isdir(settings.admin_ui_dist_path):
        return
    import json as _json
    from os.path import splitext
    from fastapi.staticfiles import StaticFiles
    from starlette.exceptions import HTTPException as StarletteHTTPException

    @app.get("/workstation/runtime-config.js", include_in_schema=False)
    def workstation_runtime_config() -> PlainTextResponse:
        payload: dict[str, str] = {}
        if settings.auth_login_url:
            payload["authLoginUrl"] = settings.auth_login_url
        body = (
            "/* Generated by ai-worker — do not cache aggressively. */\n"
            "window.__WORKSTATION_CONFIG__ = "
            + _json.dumps(payload, ensure_ascii=False)
            + ";\n"
        )
        return PlainTextResponse(
            content=body,
            media_type="application/javascript; charset=utf-8",
            # Short cache so a config change propagates within a couple
            # of minutes without the SPA hot-reloading the bundle.
            headers={"Cache-Control": "public, max-age=60"},
        )

    asset_extensions = frozenset({
        ".js", ".mjs", ".cjs", ".map", ".css", ".json", ".wasm",
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp",
        ".woff", ".woff2", ".ttf", ".eot",
    })

    class SpaStaticFiles(StaticFiles):
        async def get_response(self, path, scope):  # type: ignore[override]
            try:
                return await super().get_response(path, scope)
            except StarletteHTTPException as exc:
                if exc.status_code != 404:
                    raise
                # ``assets/`` is the Vite output dir and the most reliable
                # signal that a request is for a built artefact. The
                # extension check covers everything outside ``assets/``
                # that still looks asset-like (favicon.ico, source maps,
                # web fonts, etc.).
                last_segment = path.rsplit("/", 1)[-1]
                ext = splitext(last_segment)[1].lower()
                if path.startswith("assets/") or ext in asset_extensions:
                    raise
                return await super().get_response("index.html", scope)

    app.mount(
        "/workstation",
        SpaStaticFiles(directory=settings.admin_ui_dist_path, html=True),
        name="workstation-ui",
    )


def create_app() -> FastAPI:
    app = FastAPI(title="ai-worker", version="0.1.0", lifespan=lifespan)

    @app.get("/internal/health")
    def health() -> dict:
        """Liveness only — does not look at model state.

        The K8s livenessProbe targets this endpoint. We deliberately keep it
        decoupled from checksum / readiness so a transient model-load failure
        doesn't trigger an infinite restart loop.
        """
        return {
            "status": "UP",
            "workerId": settings.worker_id,
            "version": "0.1.0",
            "dependencies": {
                "rabbitmq": "UNKNOWN",
                "tos": "UNKNOWN",
                "modelRuntime": "UNKNOWN",
                "meetingApiCallback": "UNKNOWN",
            },
        }

    @app.get("/internal/ready")
    def ready() -> JSONResponse:
        """Phase J — readiness probe with model checksum guard.

        A model contributes ``ready=false`` only when ``_model_info`` flags
        it ``status=ERROR`` (checksum mismatch is the only failure mode the
        guard adds; other ERRORs come from the runtime itself). Models with
        no expected checksum and no loading attempts (status NOT_LOADED /
        LOADING / READY) are treated as healthy from the probe's POV — we
        don't want a cold runtime to block kubelet from routing traffic
        that will trigger the first lazy load.
        """
        models = _all_model_infos()
        failed = [m for m in models if m["status"] == "ERROR"]
        ok = not failed
        body = {
            "ready": ok,
            "models": [
                {
                    "name": m["name"],
                    "status": m["status"],
                    "lastError": m["lastError"],
                }
                for m in models
            ],
        }
        return JSONResponse(status_code=200 if ok else 503, content=body)

    @app.get("/internal/workflows/{task_id}")
    def workflow(task_id: str) -> dict:
        snapshot = workflow_state_store.get(task_id)
        if snapshot is None:
            return {"taskId": task_id, "status": "UNKNOWN", "steps": []}
        return snapshot.to_dict()

    @app.get("/metrics")
    def metrics() -> PlainTextResponse:
        # Snapshot GPU gauges right before scrape so Prometheus reads
        # fresh values without us running a separate refresh thread.
        refresh_gpu_metrics()
        return PlainTextResponse(
            content=generate_latest(),
            media_type=CONTENT_TYPE_LATEST,
        )

    @app.get("/internal/models")
    async def models(
        request: Request,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> JSONResponse:
        body = await request.body()
        if not verify_hmac_signature(
            method=request.method,
            path=str(request.url.path),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        ):
            return _error_response(
                status_code=401,
                code="MODELS_AUTH_FAILED",
                message="HMAC signature verification failed",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )
        return JSONResponse(
            status_code=200,
            content={
                "success": True,
                "data": {"models": _all_model_infos()},
                "error": None,
                "requestId": x_request_id,
                "traceId": x_trace_id,
            },
        )

    @app.post("/internal/models/warmup")
    async def warmup(
        request: Request,
        background_tasks: BackgroundTasks,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> JSONResponse:
        body = await request.body()
        if not verify_hmac_signature(
            method=request.method,
            path=str(request.url.path),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        ):
            return _error_response(
                status_code=401,
                code="MODELS_AUTH_FAILED",
                message="HMAC signature verification failed",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        bge_m3 = get_bge_m3()
        bge_reranker = get_bge_reranker()
        # Snapshot triggered-ness BEFORE adding background tasks so the
        # response reflects whether warmup did anything new. Fake mode
        # starts READY → triggered=False; real mode starts NOT_LOADED
        # the first time → triggered=True.
        triggered = bge_m3.status == "NOT_LOADED" or bge_reranker.status == "NOT_LOADED"
        background_tasks.add_task(_safe_ensure_loaded, bge_m3)
        background_tasks.add_task(_safe_ensure_loaded, bge_reranker)

        return JSONResponse(
            status_code=200,
            content={
                "success": True,
                "data": {
                    "triggered": triggered,
                    "models": _all_model_infos(),
                },
                "error": None,
                "requestId": x_request_id,
                "traceId": x_trace_id,
            },
        )

    @app.post("/internal/embed")
    async def embed(
        request: Request,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> JSONResponse:
        body = await request.body()
        if not verify_hmac_signature(
            method=request.method,
            path=str(request.url.path),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        ):
            return _error_response(
                status_code=401,
                code="EMBEDDING_AUTH_FAILED",
                message="HMAC signature verification failed",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        try:
            embed_req = EmbedRequest.model_validate_json(body)
        except Exception as exc:
            return _error_response(
                status_code=400,
                code="EMBEDDING_CONTRACT_ERROR",
                message=f"Invalid request: {exc}",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        runtime = get_bge_m3()
        # Query embedding is on the synchronous RAG hot path. Cold-start
        # blocking is real but a single-request load is what we want here:
        # the next /api/rag/query already paid the warm-up tax. Java side
        # should hit /internal/models/warmup at boot to push this off the
        # user-visible critical path.
        try:
            await runtime.ensure_loaded()
        except BgeM3RuntimeError as exc:
            return _error_response(
                status_code=503,
                code="EMBEDDING_UNAVAILABLE",
                message=f"embedding model not ready: {exc}",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        try:
            vectors = runtime.embed(embed_req.texts)
        except BgeM3RuntimeError as exc:
            return _error_response(
                status_code=503,
                code="EMBEDDING_UNAVAILABLE",
                message=f"embedding inference failed: {exc}",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        return JSONResponse(
            status_code=200,
            content={
                "success": True,
                "data": EmbedResponse(
                    modelVersion=runtime.model_version,
                    dimension=runtime.DIMENSION,
                    vectors=vectors,
                ).model_dump(),
                "error": None,
                "requestId": x_request_id,
                "traceId": x_trace_id,
            },
        )

    @app.post("/internal/rerank")
    async def rerank(
        request: Request,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> JSONResponse:
        body = await request.body()

        if not verify_hmac_signature(
            method=request.method,
            path=str(request.url.path),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        ):
            return _error_response(
                status_code=401,
                code="RERANK_AUTH_FAILED",
                message="HMAC signature verification failed",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        try:
            rerank_req = RerankRequest.model_validate_json(body)
        except Exception as exc:
            return _error_response(
                status_code=400,
                code="RERANK_CONTRACT_ERROR",
                message=f"Invalid request: {exc}",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        runtime = get_bge_reranker()
        # In fake mode ensure_loaded is a no-op; in real mode this loads
        # the model on first call and serializes concurrent callers behind
        # an asyncio.Lock. Cold-start may take 5-15s — Java side is
        # expected to have invoked POST /internal/models/warmup at boot.
        try:
            await runtime.ensure_loaded()
        except BgeRerankerRuntimeError as exc:
            return _error_response(
                status_code=503,
                code="RERANK_UNAVAILABLE",
                message=f"rerank model not ready: {exc}",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        truncated = list(rerank_req.candidates[: rerank_req.topN])
        try:
            scores = runtime.rank(rerank_req.query, [c.text for c in truncated])
        except BgeRerankerRuntimeError as exc:
            return _error_response(
                status_code=503,
                code="RERANK_UNAVAILABLE",
                message=f"rerank inference failed: {exc}",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        # Sort by score desc, breaking ties by original input order so the
        # fake-mode (already-descending) path is a no-op and the real-mode
        # path produces deterministic ranks when two candidates tie.
        indexed = list(enumerate(zip(truncated, scores)))
        indexed.sort(key=lambda item: (-item[1][1], item[0]))
        ranked = [
            RerankResultItem(
                chunkId=cand.chunkId,
                rank=rank + 1,
                rerankScore=round(float(score), 4),
            )
            for rank, (_, (cand, score)) in enumerate(indexed)
        ]

        return JSONResponse(
            status_code=200,
            content={
                "success": True,
                "data": RerankResponse(
                    modelVersion=rerank_req.modelVersion,
                    items=ranked,
                ).model_dump(),
                "error": None,
                "requestId": x_request_id,
                "traceId": x_trace_id,
            },
        )

    _mount_admin_router(app)
    _mount_admin_ui(app)
    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("ai_worker.interfaces.api.main:app", host="0.0.0.0", port=8090, reload=False)
