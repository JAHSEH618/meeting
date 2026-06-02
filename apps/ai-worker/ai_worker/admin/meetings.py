"""Meeting workstation passthrough routes for meeting-centric user flows.

These endpoints are mostly thin proxies — meeting-api owns the business logic.
Audio upload complete is intentionally just a passthrough: meeting-api creates
the ProcessingTask and runs the one-shot worker + Java LLM phases.

SSE for task progress is NOT proxied: the SPA connects directly to Java to
keep the BFF stateless (todo C3.12).
"""

from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import ok, passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency

_log = logging.getLogger(__name__)


def build_meetings_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin", tags=["admin-meetings"])

    # ── documents search (passthrough) ──────────────────────────────────
    @router.get("/documents", status_code=200)
    async def list_documents(
        q: str | None = None,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        params: dict[str, Any] = {"q": q} if q else {}
        response = await java_client.request(
            "GET", "/api/documents",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id, params=params,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/documents", status_code=200)
    async def create_document(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST", "/api/documents",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    # ── meeting lifecycle ───────────────────────────────────────────────
    @router.get("/meetings", status_code=200)
    async def list_meetings(
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        """List meetings visible to the operator.

        Proxies ``GET /api/meetings`` so the landing page on the workstation
        can render a real list instead of the "waiting for admin BFF" stub.
        Tenant + RLS scoping happens server-side via the JWT-derived
        ``TenantContext``; nothing to filter here.
        """
        response = await java_client.request(
            "GET", "/api/meetings",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/meetings", status_code=200)
    async def create_meeting(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST", "/api/meetings",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.get("/meetings/{meeting_id}", status_code=200)
    async def get_meeting(
        meeting_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        # Aggregate: meeting + latest task + speakers + minutes
        # The BFF fans out N small Java calls instead of forcing the browser
        # to do it; that's the only real-orchestration value-add here.
        meeting = await java_client.request(
            "GET", f"/api/meetings/{meeting_id}",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        if meeting.status_code != 200:
            return passthrough(meeting.status_code, meeting.content, x_request_id, x_trace_id)
        task = await java_client.request(
            "GET", f"/api/meetings/{meeting_id}/processing-tasks/latest",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        speakers = await java_client.request(
            "GET", f"/api/meetings/{meeting_id}/speakers",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        minutes = await java_client.request(
            "GET", f"/api/meetings/{meeting_id}/minutes",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        return ok({
            "meeting": _safe_json(meeting),
            "latestTask": _safe_json(task) if task.status_code == 200 else None,
            "speakers": _safe_json(speakers) if speakers.status_code == 200 else None,
            "minutes": _safe_json(minutes) if minutes.status_code == 200 else None,
        }, x_request_id, x_trace_id)

    # ── documents on a meeting (passthrough) ────────────────────────────
    @router.post("/meetings/{meeting_id}/documents:attach", status_code=200)
    async def attach_document(
        meeting_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST", f"/api/meetings/{meeting_id}/documents",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    # ── glossary (passthrough) ──────────────────────────────────────────
    @router.patch("/meetings/{meeting_id}/glossary", status_code=200)
    async def update_glossary(
        meeting_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "PATCH", f"/api/meetings/{meeting_id}/glossary",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    # ── audio uploads (passthrough) ─────────────────────────────────────
    @router.post("/meetings/{meeting_id}/files/audio/uploads", status_code=200)
    async def create_audio_upload(
        meeting_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST",
            f"/api/meetings/{meeting_id}/files/audio/uploads",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/meetings/{meeting_id}/files/audio/uploads/{upload_id}/parts", status_code=200)
    async def create_audio_upload_part(
        meeting_id: str,
        upload_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST",
            f"/api/meetings/{meeting_id}/files/audio/uploads/{upload_id}/parts",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/meetings/{meeting_id}/files/audio/uploads/{upload_id}/complete", status_code=200)
    async def complete_audio_upload(
        meeting_id: str,
        upload_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        response = await java_client.request(
            "POST",
            f"/api/meetings/{meeting_id}/files/audio/uploads/{upload_id}/complete",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/meetings/{meeting_id}/files/audio/uploads/{upload_id}/abort", status_code=200)
    async def abort_audio_upload(
        meeting_id: str,
        upload_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        response = await java_client.request(
            "POST",
            f"/api/meetings/{meeting_id}/files/audio/uploads/{upload_id}/abort",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    # ── speaker confirm (passthrough) ───────────────────────────────────
    @router.post("/meetings/{meeting_id}/speakers/{label}:confirm", status_code=200)
    async def confirm_speaker(
        meeting_id: str,
        label: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json() if (await request.body()) else {}
        response = await java_client.request(
            "POST", f"/api/meetings/{meeting_id}/speakers/{label}/confirm",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/meetings/{meeting_id}/speakers/{label}:reject", status_code=200)
    async def reject_speaker(
        meeting_id: str,
        label: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        response = await java_client.request(
            "POST", f"/api/meetings/{meeting_id}/speakers/{label}/reject",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    # ── exports (passthrough) ───────────────────────────────────────────
    @router.post("/meetings/{meeting_id}/exports", status_code=200)
    async def create_export(
        meeting_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json() if (await request.body()) else {"format": "DOCX"}
        response = await java_client.request(
            "POST", f"/api/meetings/{meeting_id}/exports",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.get("/meetings/{meeting_id}/exports/{job_id}", status_code=200)
    async def get_export(
        meeting_id: str,
        job_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        response = await java_client.request(
            "GET", f"/api/exports/{job_id}",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    return router


def _safe_json(response: Any) -> dict[str, Any] | None:
    try:
        return json.loads(response.content)
    except Exception:
        return None
