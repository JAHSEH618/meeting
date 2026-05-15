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
from ai_worker.model_runtime.embedding import BgeM3Runtime, BgeM3RuntimeError
from ai_worker.model_runtime.registry import get_bge_m3, get_bge_reranker
from ai_worker.model_runtime.rerank import (
    BgeRerankerRuntime,
    BgeRerankerRuntimeError,
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


def _model_info(runtime: BgeM3Runtime | BgeRerankerRuntime, name: str) -> dict:
    """Project a runtime into a `ModelInfo` matching ai-worker-internal-api.yaml.

    `checksum` is reserved for post-M5-0 work: once weights are pinned to
    docs/model-registry.md, this will surface the loaded file's SHA-256.
    `modelsDir` is populated when a local snapshot was used (real-mode path).
    """
    models_dir: str | None = None
    if isinstance(runtime, BgeM3Runtime):
        models_dir = settings.bge_m3_models_dir
    elif isinstance(runtime, BgeRerankerRuntime):
        models_dir = settings.bge_reranker_models_dir
    return {
        "name": name,
        "version": runtime.model_version,
        "status": runtime.status,
        "device": runtime.device,
        "useFake": runtime.use_fake,
        "checksum": None,
        "modelsDir": models_dir,
        "lastError": runtime.last_error,
    }


def _all_model_infos() -> list[dict]:
    return [
        _model_info(get_bge_m3(), "bge-m3"),
        _model_info(get_bge_reranker(), "bge-reranker-v2-m3"),
    ]


async def _safe_ensure_loaded(
    runtime: BgeM3Runtime | BgeRerankerRuntime,
) -> None:
    """Background-task wrapper that swallows load failures.

    Errors are surfaced via `runtime.status == "ERROR"` and `last_error`
    so the next request can return a 503 with full context — we just don't
    want an unhandled exception to escape the background task.
    """
    try:
        await runtime.ensure_loaded()
    except Exception:
        pass


def create_app() -> FastAPI:
    app = FastAPI(title="ai-worker", version="0.1.0")

    @app.get("/internal/health")
    def health() -> dict:
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

    @app.get("/internal/workflows/{task_id}")
    def workflow(task_id: str) -> dict:
        snapshot = workflow_state_store.get(task_id)
        if snapshot is None:
            return {"taskId": task_id, "status": "UNKNOWN", "steps": []}
        return snapshot.to_dict()

    @app.get("/metrics")
    def metrics() -> PlainTextResponse:
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

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("ai_worker.interfaces.api.main:app", host="0.0.0.0", port=8090, reload=False)
