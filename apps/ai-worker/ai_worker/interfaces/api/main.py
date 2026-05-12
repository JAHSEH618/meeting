from fastapi import FastAPI, Header, HTTPException, Request

from ai_worker.common.config import settings
from ai_worker.infrastructure.internal_api.auth import (
    RerankRequest,
    RerankResponse,
    RerankResultItem,
    verify_hmac_signature,
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
        return {
            "taskId": task_id,
            "status": "UNKNOWN",
            "steps": [],
        }

    @app.post("/internal/rerank")
    async def rerank(
        request: Request,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> dict:
        body = await request.body()

        if not verify_hmac_signature(
            method=request.method,
            path=str(request.url.path),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        ):
            raise HTTPException(
                status_code=401,
                detail={
                    "success": False,
                    "data": None,
                    "error": {
                        "code": "RERANK_AUTH_FAILED",
                        "message": "HMAC signature verification failed",
                        "retryable": False,
                    },
                    "requestId": x_request_id,
                    "traceId": x_trace_id,
                },
            )

        try:
            rerank_req = RerankRequest.model_validate_json(body)
        except Exception as exc:
            raise HTTPException(
                status_code=400,
                detail={
                    "success": False,
                    "data": None,
                    "error": {
                        "code": "RERANK_CONTRACT_ERROR",
                        "message": f"Invalid request: {exc}",
                        "retryable": False,
                    },
                    "requestId": x_request_id,
                    "traceId": x_trace_id,
                },
            )

        ranked = []
        for i, candidate in enumerate(rerank_req.candidates[: rerank_req.topN]):
            ranked.append(
                RerankResultItem(
                    chunkId=candidate.chunkId,
                    rank=i + 1,
                    rerankScore=round(1.0 - i * 0.05, 4),
                )
            )

        return {
            "success": True,
            "data": RerankResponse(
                modelVersion=rerank_req.modelVersion,
                items=ranked,
            ).model_dump(),
            "error": None,
            "requestId": x_request_id,
            "traceId": x_trace_id,
        }

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("ai_worker.interfaces.api.main:app", host="0.0.0.0", port=8090, reload=False)