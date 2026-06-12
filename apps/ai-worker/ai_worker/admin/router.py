"""Build the admin router and provide a fail-fast factory hook."""

from __future__ import annotations

from fastapi import APIRouter, FastAPI, Request

from ai_worker.admin.enrollment import build_enrollment_router, build_voiceprint_router
from ai_worker.admin.envelopes import MalformedJsonBodyError, error
from ai_worker.admin.files import build_files_router
from ai_worker.admin.java_client import JavaPublicClient, UpstreamUnavailableError
from ai_worker.admin.meetings import build_meetings_router
from ai_worker.admin.persons import build_persons_router
from ai_worker.admin.session_store import EnrollmentSessionStore, enrollment_session_store


def register_admin_exception_handlers(app: FastAPI) -> None:
    """502/400 envelopes for BFF failure modes (I11). Must be attached to the
    FastAPI app (handlers cannot live on an APIRouter)."""

    @app.exception_handler(UpstreamUnavailableError)
    async def _upstream_unavailable(request: Request, exc: UpstreamUnavailableError):
        return error(
            status_code=502,
            code="UPSTREAM_UNAVAILABLE",
            message=str(exc),
            retryable=True,
            request_id=request.headers.get("X-Request-Id"),
            trace_id=request.headers.get("X-Trace-Id"),
        )

    @app.exception_handler(MalformedJsonBodyError)
    async def _malformed_json(request: Request, exc: MalformedJsonBodyError):
        return error(
            status_code=400,
            code="VALIDATION_FAILED",
            message=str(exc),
            retryable=False,
            request_id=request.headers.get("X-Request-Id"),
            trace_id=request.headers.get("X-Trace-Id"),
        )


def build_admin_router(
    *,
    java_client: JavaPublicClient | None = None,
    session_store: EnrollmentSessionStore | None = None,
) -> APIRouter:
    """Build the full /admin/* router. Tests pass mock java_client + session_store."""
    client = java_client or JavaPublicClient()
    store = session_store or enrollment_session_store
    parent = APIRouter()
    parent.include_router(build_enrollment_router(java_client=client, session_store=store))
    parent.include_router(build_voiceprint_router(java_client=client))
    parent.include_router(build_persons_router(java_client=client))
    parent.include_router(build_files_router(java_client=client))
    parent.include_router(build_meetings_router(java_client=client))
    return parent
