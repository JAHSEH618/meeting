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

    return app


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("ai_worker.interfaces.api.main:app", host="0.0.0.0", port=8090, reload=False)
