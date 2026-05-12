from fastapi import FastAPI

from ai_worker.common.config import settings


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
    def rerank(request: dict) -> dict:
        query = request.get("query", "")
        candidates = request.get("candidates", [])
        top_n = request.get("topN", 8)
        model_version = request.get("modelVersion", "placeholder-v0")

        if not query or not candidates:
            return {
                "modelVersion": model_version,
                "items": [],
            }

        ranked = []
        for i, candidate in enumerate(candidates[:top_n]):
            ranked.append({
                "chunkId": candidate.get("chunkId", f"chunk_{i}"),
                "rank": i + 1,
                "rerankScore": round(1.0 - i * 0.05, 4),
            })

        return {
            "modelVersion": model_version,
            "items": ranked,
        }

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("ai_worker.interfaces.api.main:app", host="0.0.0.0", port=8090, reload=False)
