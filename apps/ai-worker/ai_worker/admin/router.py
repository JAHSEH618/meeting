"""Build the admin router and provide a fail-fast factory hook."""

from __future__ import annotations

from fastapi import APIRouter

from ai_worker.admin.enrollment import build_enrollment_router, build_voiceprint_router
from ai_worker.admin.files import build_files_router
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.meetings import build_meetings_router
from ai_worker.admin.persons import build_persons_router
from ai_worker.admin.session_store import EnrollmentSessionStore, enrollment_session_store


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
