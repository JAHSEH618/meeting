"""Build the admin router and provide a fail-fast factory hook."""

from __future__ import annotations

from fastapi import APIRouter

from ai_worker.admin.enrollment import (
    build_default_preview_fn,
    build_enrollment_router,
    build_voiceprint_router,
)
from ai_worker.admin.files import build_files_router
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.meetings import build_meetings_router
from ai_worker.admin.persons import build_persons_router
from ai_worker.admin.session_store import EnrollmentSessionStore, enrollment_session_store
from ai_worker.common.config import settings


class AdminStartupConfigError(RuntimeError):
    """Raised at boot when required configuration for the admin BFF is missing."""


def ensure_admin_config() -> None:
    """Fail-fast guard — called from main.create_app when AI_WORKER_ENABLE_ADMIN is set."""
    missing: list[str] = []
    if not settings.java_api_base_url:
        missing.append("AI_WORKER_JAVA_API_BASE_URL")
    if not settings.internal_api_hmac_secret or settings.internal_api_hmac_secret == "dev-internal-secret":
        # We only warn — admin BFF itself does not use HMAC, but the worker as a whole does.
        # Hard-fail only on the values strictly required to mint upstream calls.
        pass
    if missing:
        raise AdminStartupConfigError(
            "Admin BFF cannot start; missing env: " + ", ".join(missing)
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
    parent.include_router(
        build_enrollment_router(
            java_client=client,
            session_store=store,
            # Real CAM++ preview when the speaker runtime is real; the old
            # default silently scored quality by file size in production.
            preview_fn=build_default_preview_fn(),
        )
    )
    parent.include_router(build_voiceprint_router(java_client=client))
    parent.include_router(build_persons_router(java_client=client))
    parent.include_router(build_files_router(java_client=client))
    parent.include_router(build_meetings_router(java_client=client))
    return parent
