from contextlib import asynccontextmanager
from os.path import isdir
from typing import AsyncIterator

import httpx
from fastapi import BackgroundTasks, Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse, PlainTextResponse, RedirectResponse, Response, StreamingResponse
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import settings
from ai_worker.infrastructure.internal_api.auth import (
    EmbedRequest,
    EmbedResponse,
    HmacAuthError,
    RerankRequest,
    RerankResponse,
    RerankResultItem,
    VerifiedInternalRequest,
    require_hmac,
)
from ai_worker.model_runtime.asr import Qwen3AsrRuntime
from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime
from ai_worker.model_runtime.embedding import BgeM3Runtime, BgeM3RuntimeError
from ai_worker.model_runtime.registry import (
    get_asr_runtime,
    get_bge_m3,
    get_bge_reranker,
    get_diarization_runtime,
    get_speaker_runtime,
    resolve_devices_snapshot,
)
from ai_worker.model_runtime.rerank import (
    BgeRerankerRuntime,
    BgeRerankerRuntimeError,
)
from ai_worker.model_runtime.speaker import CamPlusPlusRuntime
from ai_worker.observability.gpu_metrics import refresh_gpu_metrics
from ai_worker.observability.model_checksum import compute_checksum_cached


RuntimeLike = (
    BgeM3Runtime
    | BgeRerankerRuntime
    | Qwen3AsrRuntime
    | PyannoteDiarizationRuntime
    | CamPlusPlusRuntime
)


def _java_proxy_client(request: Request) -> httpx.AsyncClient:
    """Return the app-scoped pooled httpx client for the same-origin polling
    proxies, lazily creating + caching it on app.state.

    The lifespan pre-creates and closes it in production; the lazy fallback keeps
    the proxies working in contexts where the lifespan didn't run (e.g. a
    TestClient used without its context manager). Either way callers share one
    pooled client instead of building a fresh one (new pool + TLS) per request.
    """
    client = getattr(request.app.state, "java_proxy_client", None)
    if client is None:
        client = httpx.AsyncClient(
            base_url=settings.java_api_base_url.rstrip("/"), timeout=10.0
        )
        request.app.state.java_proxy_client = client
    return client


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
    if isinstance(runtime, CamPlusPlusRuntime):
        return settings.cam_plus_models_dir
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
    if isinstance(runtime, CamPlusPlusRuntime):
        return settings.cam_plus_expected_checksum
    return None


def _required_package_for(runtime: RuntimeLike) -> str | None:
    """Python package the real-mode runtime needs to load weights.

    ``None`` for fake-only paths so callers can shortcut. Mirrors the
    lazy imports inside each runtime's ``_load_model_blocking`` so this
    is the single point of truth — if a runtime gains a new dep, add
    the entry here and the readiness probe picks it up.
    """
    if isinstance(runtime, (BgeM3Runtime, BgeRerankerRuntime)):
        return "FlagEmbedding"
    if isinstance(runtime, Qwen3AsrRuntime):
        return "funasr"
    if isinstance(runtime, PyannoteDiarizationRuntime):
        return "pyannote.audio"
    if isinstance(runtime, CamPlusPlusRuntime):
        return "modelscope"
    return None


def _package_importable(name: str) -> bool:
    """``importlib.util.find_spec`` raises on dotted names whose parent
    module is absent (e.g. ``pyannote.audio`` when ``pyannote`` itself
    isn't installed). Swallow that as "not importable" so readiness can
    decide without a 500."""
    from importlib import util

    try:
        return util.find_spec(name) is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def _model_info(runtime: RuntimeLike, name: str, *, compute_actual_checksum: bool = True) -> dict:
    """Project a runtime into ``ModelInfo`` per ai-worker-internal-api.yaml.

    Three layered concerns (later wins):
      1. ``checksum`` is computed lazily from on-disk weight files when
         ``modelsDir`` is set (Phase 8.4.1.b). Falls back to ``None`` for
         fake mode / HF-fallback paths so callers can tell "we don't know"
         from "this is the pinned hash".
      2. Phase J4 checksum guard: when ``AI_WORKER_*_EXPECTED_CHECKSUM``
         is configured, mismatch (or missing weights when the guard is
         armed) forces ``status=ERROR`` with a ``lastError`` describing
         the divergence — without mutating the shared runtime singleton.
      3. Phase J ML hardening: real-mode (``use_fake=False``) runtime
         whose Python dep (FlagEmbedding / funasr / pyannote.audio) isn't
         importable forces ``status=ERROR``. This catches the "image
         built without ``UV_EXTRAS=real-models`` but the ConfigMap flipped
         fake=false" footgun *before* the first task lands; without it
         the pod would Ready, accept work, and crash on first inference.
    """
    models_dir = _models_dir_for(runtime)
    expected = _expected_checksum_for(runtime)
    # Hash on-disk weights only when the value is actually needed:
    #   * an expected checksum is configured (the J4 guard must compare), or
    #   * the caller wants the hash echoed (the /internal/models surface).
    # The readiness probe passes ``compute_actual_checksum=False`` so a deploy
    # with no expected checksums never hashes multi-GB weights on the probe
    # hot path. ``compute_checksum_cached`` memoizes so even when we do hash,
    # it happens once per process rather than every probe.
    actual = (
        compute_checksum_cached(models_dir)
        if compute_actual_checksum or expected is not None
        else None
    )

    status = runtime.status
    last_error = runtime.last_error
    if expected is not None and actual != expected:
        status = "ERROR"
        observed = actual or "<no weights found>"
        last_error = f"checksum mismatch: expected {expected} got {observed}"

    if not runtime.use_fake:
        required = _required_package_for(runtime)
        if required and not _package_importable(required):
            status = "ERROR"
            last_error = (
                f"real-mode runtime requires {required} but the package is not "
                "importable; rebuild the image with UV_EXTRAS=real-models "
                "(or the matching subset)"
            )

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


def _all_model_infos(*, compute_actual_checksum: bool = True) -> list[dict]:
    return [
        _model_info(get_bge_m3(), "bge-m3", compute_actual_checksum=compute_actual_checksum),
        _model_info(get_bge_reranker(), "bge-reranker-v2-m3", compute_actual_checksum=compute_actual_checksum),
        _model_info(get_asr_runtime(), "qwen3-asr", compute_actual_checksum=compute_actual_checksum),
        _model_info(get_diarization_runtime(), "pyannote-diarization", compute_actual_checksum=compute_actual_checksum),
        _model_info(get_speaker_runtime(), "cam++-speaker", compute_actual_checksum=compute_actual_checksum),
    ]


async def _safe_ensure_loaded(runtime: RuntimeLike) -> None:
    """Background-task wrapper that swallows load failures.

    Errors are surfaced via ``runtime.status == "ERROR"`` and ``last_error``
    so the next request can return a 503 with full context — we just don't
    want an unhandled exception to escape the background task.
    """
    try:
        await runtime.ensure_loaded()
    except Exception:
        pass


def _hardware_snapshot() -> dict:
    """Static-ish snapshot of the host's ML capabilities.

    Used by ``GET /internal/hardware`` so operators can answer
    "did my Mac MPS get picked up?" / "is funasr installed?" without SSH'ing
    into the container. Imports torch lazily — fake-mode deployments don't
    pay the torch import cost just for the health check.
    """
    torch_available = False
    torch_version: str | None = None
    cuda_available = False
    cuda_device_count = 0
    mps_built = False
    mps_available = False
    probe_error: str | None = None
    try:
        import torch  # type: ignore[import-not-found]

        torch_available = True
        torch_version = torch.__version__
        cuda_available = bool(torch.cuda.is_available())
        cuda_device_count = int(torch.cuda.device_count()) if cuda_available else 0
        mps = getattr(getattr(torch, "backends", None), "mps", None)
        if mps is not None:
            mps_built = bool(getattr(mps, "is_built", lambda: False)())
            mps_available = bool(mps.is_available())
    except ImportError:
        pass
    except (OSError, RuntimeError) as exc:
        # CUDA driver/lib version mismatch raises OSError on dlopen and
        # RuntimeError on torch.cuda.* — surface the message so operators
        # can spot it from /internal/hardware without grepping logs.
        probe_error = f"{type(exc).__name__}: {exc}"

    def _pkg_available(name: str) -> bool:
        return _package_importable(name)

    return {
        "torch": {
            "installed": torch_available,
            "version": torch_version,
            "cuda": {"available": cuda_available, "deviceCount": cuda_device_count},
            "mps": {"built": mps_built, "available": mps_available},
            "probeError": probe_error,
        },
        "packages": {
            "FlagEmbedding": _pkg_available("FlagEmbedding"),
            "funasr": _pkg_available("funasr"),
            "pyannote.audio": _pkg_available("pyannote.audio"),
            "modelscope": _pkg_available("modelscope"),
            "pynvml": _pkg_available("pynvml"),
        },
        "resolvedDevices": resolve_devices_snapshot(),
    }


_CAPABILITY_TO_RUNTIMES = {
    "embedding": ("bge-m3", lambda: get_bge_m3()),
    "rerank": ("bge-reranker-v2-m3", lambda: get_bge_reranker()),
    "asr": ("qwen3-asr", lambda: get_asr_runtime()),
    "diarization": ("pyannote-diarization", lambda: get_diarization_runtime()),
    "speaker": ("cam++-speaker", lambda: get_speaker_runtime()),
}


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Phase J — replaces deprecated ``@app.on_event``.

    The enrollment session cleanup loop is only wired when the workstation
    BFF is enabled (``AI_WORKER_JAVA_API_BASE_URL`` set); otherwise the
    admin module isn't mounted and starting a cleanup task would be dead
    weight.
    """
    # Pooled httpx client for the same-origin polling proxies (login + task
    # detail) so they reuse one connection pool instead of building a fresh
    # client (new pool + TLS handshake) per request. The SPA polls task-detail
    # frequently, so this removes steady connection churn. The long-lived SSE
    # stream keeps its own per-request client (it needs a much longer timeout).
    _app.state.java_proxy_client = (
        httpx.AsyncClient(base_url=settings.java_api_base_url.rstrip("/"), timeout=10.0)
        if settings.java_api_base_url
        else None
    )
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
        proxy_client = getattr(_app.state, "java_proxy_client", None)
        aclose = getattr(proxy_client, "aclose", None) if proxy_client is not None else None
        if aclose is not None:
            await aclose()


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


def _mount_auth_login_proxy(app: FastAPI) -> None:
    """Same-origin login proxy for the Python-hosted workstation SPA.

    The built SPA posts to ``/api/auth/login``. In Vite dev this is handled by
    the Vite proxy; when FastAPI serves ``dist/`` itself, this route forwards
    just that unauthenticated login call to meeting-api. Authenticated business
    calls still go through the explicit ``/admin/*`` BFF routes.
    """

    @app.post("/api/auth/login", include_in_schema=False)
    async def proxy_java_auth_login(
        request: Request,
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ) -> Response:
        request_id = x_request_id or ""
        trace_id = x_trace_id or ""
        if not settings.java_api_base_url:
            return _error_response(
                status_code=503,
                code="UPSTREAM_NOT_CONFIGURED",
                message="AI_WORKER_JAVA_API_BASE_URL is not configured",
                retryable=False,
                request_id=request_id,
                trace_id=trace_id,
            )
        try:
            payload = await request.json()
        except Exception:
            return _error_response(
                status_code=400,
                code="BAD_REQUEST",
                message="login request body must be JSON",
                retryable=False,
                request_id=request_id,
                trace_id=trace_id,
            )

        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
        }
        if request_id:
            headers["X-Request-Id"] = request_id
        if trace_id:
            headers["X-Trace-Id"] = trace_id
        client = _java_proxy_client(request)
        try:
            upstream = await client.post("/api/auth/login", json=payload, headers=headers)
        except httpx.RequestError as exc:
            return _error_response(
                status_code=502,
                code="UPSTREAM_UNAVAILABLE",
                message=f"meeting-api auth login unavailable: {exc}",
                retryable=True,
                request_id=request_id,
                trace_id=trace_id,
            )

        response_headers = {}
        content_type = upstream.headers.get("content-type")
        if content_type:
            response_headers["content-type"] = content_type
        response = Response(
            content=upstream.content,
            status_code=upstream.status_code,
            headers=response_headers,
        )
        for value in upstream.headers.get_list("set-cookie"):
            response.raw_headers.append((b"set-cookie", value.encode("latin-1")))
        return response


def _mount_processing_task_proxy(app: FastAPI) -> None:
    """Narrow same-origin proxy for workstation task polling + SSE.

    The workstation bundle is hosted by ai-worker under ``/workstation/``.
    Native browser EventSource cannot attach an Authorization header, so the
    frontend uses fetch-stream SSE and this route forwards only the task
    read surfaces it needs to Java. General business writes remain under the
    explicit ``/admin/*`` BFF.
    """

    def upstream_path(request: Request) -> str:
        raw_path = request.scope.get("raw_path")
        if isinstance(raw_path, bytes):
            return raw_path.decode("ascii")
        return request.url.path

    def proxy_headers(request: Request, *, accept: str) -> dict[str, str]:
        headers = {"Accept": accept}
        for name in ("Authorization", "X-Request-Id", "X-Trace-Id", "Last-Event-Id"):
            value = request.headers.get(name)
            if value:
                headers[name] = value
        return headers

    @app.get("/api/processing-tasks/{task_id}/events", include_in_schema=False)
    async def proxy_processing_task_events(request: Request, task_id: str) -> Response:
        if not settings.java_api_base_url:
            async def unavailable() -> AsyncIterator[bytes]:
                yield b'event: ERROR\ndata: {"code":"UPSTREAM_NOT_CONFIGURED"}\n\n'

            return StreamingResponse(
                unavailable(),
                status_code=503,
                media_type="text/event-stream",
            )

        path = upstream_path(request)
        headers = proxy_headers(request, accept="text/event-stream")

        client = httpx.AsyncClient(
            base_url=settings.java_api_base_url.rstrip("/"),
            timeout=120.0,
        )
        stream_context = client.stream("GET", path, headers=headers)
        try:
            upstream = await stream_context.__aenter__()
        except httpx.RequestError as exc:
            await client.aclose()
            return _error_response(
                status_code=502,
                code="UPSTREAM_UNAVAILABLE",
                message=f"meeting-api processing task events unavailable: {exc}",
                retryable=True,
                request_id=request.headers.get("X-Request-Id", ""),
                trace_id=request.headers.get("X-Trace-Id", ""),
            )

        async def stream() -> AsyncIterator[bytes]:
            try:
                async for chunk in upstream.aiter_bytes():
                    yield chunk
            finally:
                await stream_context.__aexit__(None, None, None)
                await client.aclose()

        response_headers: dict[str, str] = {}
        cache_control = upstream.headers.get("cache-control")
        if cache_control:
            response_headers["Cache-Control"] = cache_control
        elif upstream.status_code < 400:
            response_headers["Cache-Control"] = "no-cache"

        return StreamingResponse(
            stream(),
            status_code=upstream.status_code,
            media_type=upstream.headers.get("content-type") or "text/event-stream",
            headers=response_headers,
        )

    @app.get("/api/processing-tasks/{task_id}", include_in_schema=False)
    async def proxy_processing_task_detail(request: Request, task_id: str) -> Response:
        request_id = request.headers.get("X-Request-Id", "")
        trace_id = request.headers.get("X-Trace-Id", "")
        if not settings.java_api_base_url:
            return _error_response(
                status_code=503,
                code="UPSTREAM_NOT_CONFIGURED",
                message="AI_WORKER_JAVA_API_BASE_URL is not configured",
                retryable=False,
                request_id=request_id,
                trace_id=trace_id,
            )
        client = _java_proxy_client(request)
        try:
            upstream = await client.get(
                upstream_path(request),
                headers=proxy_headers(request, accept="application/json"),
            )
        except httpx.RequestError as exc:
            return _error_response(
                status_code=502,
                code="UPSTREAM_UNAVAILABLE",
                message=f"meeting-api processing task unavailable: {exc}",
                retryable=True,
                request_id=request_id,
                trace_id=trace_id,
            )

        response_headers = {}
        content_type = upstream.headers.get("content-type")
        if content_type:
            response_headers["content-type"] = content_type
        return Response(
            content=upstream.content,
            status_code=upstream.status_code,
            headers=response_headers,
        )


def _mount_admin_ui(app: FastAPI) -> None:
    """Phase 9 P6 / E1.2 — mount the workstation SPA at ``/workstation/`` when
    a build artefact dir is configured. Kept separate from ``/admin/*`` so the
    BFF routes don't collide with the static file handler.

    Uses a small ``StaticFiles`` subclass that falls back to ``index.html``
    only for SPA-route 404s (no file extension, not under ``assets/``).
    Asset 404s are intentionally left as real 404s so the browser surfaces
    a clear network error instead of silently parsing ``index.html`` as a
    JS module / stylesheet (Phase J UX hardening).

    Also exposes ``GET /workstation/runtime-config.json`` so the SPA can read
    ``window.__WORKSTATION_CONFIG__`` and avoid rebuilding the image when an
    environment-specific URL (e.g. Java login) changes. The explicit route
    must be registered BEFORE the mount or the static handler shadows it.
    """
    if not settings.admin_ui_dist_path:
        return
    if not isdir(settings.admin_ui_dist_path):
        return
    from os.path import splitext
    from fastapi.staticfiles import StaticFiles
    from starlette.exceptions import HTTPException as StarletteHTTPException

    @app.get("/workstation/runtime-config.json", include_in_schema=False)
    def workstation_runtime_config() -> JSONResponse:
        """Phase J runtime config — keep this strict JSON so the SPA can
        fetch + parse it without a classic ``<script>`` tag (which Vite
        can't bundle and surfaces as a build warning). The bundle reads
        this endpoint once at bootstrap and assigns the body to
        ``window.__WORKSTATION_CONFIG__`` before mounting React.
        """
        payload: dict[str, str] = {}
        if settings.auth_login_url:
            payload["authLoginUrl"] = settings.auth_login_url
        return JSONResponse(
            content=payload,
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
    # Hard-fail at startup if the internal-API / admin-JWT secrets are still the
    # shipped defaults (unless AI_WORKER_ALLOW_INSECURE_SECRETS is set for dev).
    from ai_worker.common.config import validate_security_config

    validate_security_config()

    app = FastAPI(title="ai-worker", version="0.1.0", lifespan=lifespan)

    @app.exception_handler(HmacAuthError)
    async def _hmac_auth_error_handler(_request: Request, exc: HmacAuthError) -> JSONResponse:
        # Single place that renders the canonical 401 envelope for a failed
        # internal-API HMAC check (raised by the require_hmac dependency).
        return _error_response(
            status_code=401,
            code=exc.code,
            message="HMAC signature verification failed",
            retryable=False,
            request_id=exc.request_id,
            trace_id=exc.trace_id,
        )

    @app.get("/", include_in_schema=False)
    def root():
        if settings.admin_ui_dist_path and isdir(settings.admin_ui_dist_path):
            return RedirectResponse(url="/workstation/", status_code=307)
        return JSONResponse(
            content={
                "status": "UP",
                "workstationUrl": "/workstation/",
                "workstationMounted": False,
                "healthUrl": "/internal/health",
            }
        )

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
        # Readiness must be cheap: it never echoes the checksum (see body
        # below), and only needs the on-disk hash for models that have an
        # expected checksum configured. Passing compute_actual_checksum=False
        # keeps a no-expected-checksum deploy from hashing weights here.
        models = _all_model_infos(compute_actual_checksum=False)
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

    @app.get("/internal/hardware")
    def hardware() -> JSONResponse:
        """Phase J ML hardening — diagnostic surface for operators.

        Reports torch / CUDA / MPS / dependency-package availability and
        the device each model singleton would resolve to with the current
        environment. Intentionally unauthenticated GET (mirrors
        ``/internal/health`` + ``/internal/ready``) because the response
        contains no tenant data — just host capabilities.
        """
        return JSONResponse(status_code=200, content=_hardware_snapshot())

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
        ctx: VerifiedInternalRequest = Depends(require_hmac("MODELS")),
    ) -> JSONResponse:
        return JSONResponse(
            status_code=200,
            content={
                "success": True,
                "data": {"models": _all_model_infos()},
                "error": None,
                "requestId": ctx.request_id,
                "traceId": ctx.trace_id,
            },
        )

    @app.post("/internal/models/warmup")
    async def warmup(
        request: Request,
        background_tasks: BackgroundTasks,
        ctx: VerifiedInternalRequest = Depends(require_hmac("MODELS")),
    ) -> JSONResponse:
        x_request_id = ctx.request_id
        x_trace_id = ctx.trace_id
        # Capability-aware warmup. ``?capabilities=asr,diarization`` (comma-
        # separated, case-insensitive) restricts to a subset; absent or
        # ``all`` triggers everything. Embedding + rerank are still the
        # default subset for back-compat with the Java side that hits
        # this endpoint at boot — keeping it close to the old behaviour
        # unless a caller explicitly opts into ASR/DIAR warmup (which is
        # heavy and not worth doing on a CPU-only dev box).
        requested = request.query_params.get("capabilities", "embedding,rerank")
        if requested.strip().lower() == "all":
            wanted = set(_CAPABILITY_TO_RUNTIMES.keys())
        else:
            wanted = {c.strip().lower() for c in requested.split(",") if c.strip()}
        unknown = wanted - set(_CAPABILITY_TO_RUNTIMES.keys())
        if unknown:
            return _error_response(
                status_code=400,
                code="WARMUP_UNKNOWN_CAPABILITY",
                message=f"unknown capability: {sorted(unknown)}",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )
        runtimes_to_warm: list[RuntimeLike] = [
            _CAPABILITY_TO_RUNTIMES[c][1]() for c in sorted(wanted)
        ]
        # Snapshot triggered-ness BEFORE adding background tasks so the
        # response reflects whether warmup did anything new. Fake mode
        # starts READY → triggered=False; real mode starts NOT_LOADED
        # the first time → triggered=True.
        triggered = any(r.status == "NOT_LOADED" for r in runtimes_to_warm)
        for runtime in runtimes_to_warm:
            background_tasks.add_task(_safe_ensure_loaded, runtime)

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
        ctx: VerifiedInternalRequest = Depends(require_hmac("EMBEDDING")),
    ) -> JSONResponse:
        x_request_id = ctx.request_id
        x_trace_id = ctx.trace_id
        body = ctx.body

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
            vectors = await runtime.aembed(embed_req.texts)
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
        ctx: VerifiedInternalRequest = Depends(require_hmac("RERANK")),
    ) -> JSONResponse:
        x_request_id = ctx.request_id
        x_trace_id = ctx.trace_id
        body = ctx.body

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

        # Score the WHOLE candidate pool, not just the first topN. The point of
        # a cross-encoder reranker is to promote candidates that hybrid/RRF
        # retrieval ranked low; truncating to topN BEFORE scoring would make
        # rerank a no-op reordering of the already-top-N RRF results. We slice
        # to topN only AFTER sorting by rerank score.
        candidates = list(rerank_req.candidates)
        try:
            scores = await runtime.arank(rerank_req.query, [c.text for c in candidates])
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
        # path produces deterministic ranks when two candidates tie. Take the
        # top `topN` after the full-pool sort.
        indexed = list(enumerate(zip(candidates, scores)))
        indexed.sort(key=lambda item: (-item[1][1], item[0]))
        ranked = [
            RerankResultItem(
                chunkId=cand.chunkId,
                rank=rank + 1,
                # Clamp to the contract's [0, 1] range so a model that returns a
                # marginally out-of-range score can't 500 the response.
                rerankScore=round(min(1.0, max(0.0, float(score))), 4),
            )
            for rank, (_, (cand, score)) in enumerate(indexed[: rerank_req.topN])
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
    _mount_auth_login_proxy(app)
    _mount_processing_task_proxy(app)
    _mount_admin_ui(app)
    return app


app = create_app()


def run() -> None:
    import os
    import uvicorn

    # Honour AI_WORKER_API_PORT (the local-control / compose scripts pass it
    # and then health-check that port). Falls back to 8090, the documented
    # default and the k8s containerPort.
    port = int(os.environ.get("AI_WORKER_API_PORT", "8090"))
    host = os.environ.get("AI_WORKER_API_HOST", "0.0.0.0")
    uvicorn.run("ai_worker.interfaces.api.main:app", host=host, port=port, reload=False)
