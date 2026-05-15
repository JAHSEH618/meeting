from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import settings
from ai_worker.infrastructure.internal_api.auth import (
    RerankRequest,
    RerankResponse,
    RerankResultItem,
    verify_hmac_signature,
)
from ai_worker.model_runtime.registry import get_bge_reranker
from ai_worker.model_runtime.rerank import BgeRerankerRuntimeError


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

    @app.get("/internal/models")
    def models() -> dict:
        return {"models": []}

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
