"""Meeting workstation orchestration — D1 / D2 / D3 / D4 user flows.

These endpoints are mostly thin proxies — meeting-api owns the business logic.
Two routes do real orchestration:

* ``POST /admin/meetings/{id}:start-processing`` adds ``holdAtWorkerPhase=true``
  to the create-task body so Java will halt at ``WORKER_DAG_DONE`` and wait
  for the user.
* ``POST /admin/meetings/{id}:finalize`` calls Java's
  ``/api/processing-tasks/{taskId}:resume-java-phase`` after looking up the
  latest task id for the meeting.

SSE for task progress is NOT proxied: the SPA connects directly to Java to
keep the BFF stateless (todo C3.12).
"""

from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import error, ok, passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency

_log = logging.getLogger(__name__)


def build_meetings_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin", tags=["admin-meetings"])

    # ── persons / documents search (passthrough) ────────────────────────
    @router.get("/persons", status_code=200)
    async def list_persons(
        q: str | None = None,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        params: dict[str, Any] = {"q": q} if q else {}
        response = await java_client.request(
            "GET", "/api/persons",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id, params=params,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

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

    # ── meeting lifecycle ───────────────────────────────────────────────
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

    # ── start processing (D3 hold flag) ─────────────────────────────────
    @router.post("/meetings/{meeting_id}:start-processing", status_code=200)
    async def start_processing(
        meeting_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body: dict[str, Any] = {}
        if await request.body():
            body = await request.json()
        body.setdefault("taskType", "MEETING_FULL_PIPELINE")
        body["holdAtWorkerPhase"] = True  # D3 — workstation always holds before LLM
        response = await java_client.request(
            "POST", f"/api/meetings/{meeting_id}/processing-tasks",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
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

    # ── finalize: look up task id, call resume-java-phase ───────────────
    @router.post("/meetings/{meeting_id}:finalize", status_code=200)
    async def finalize(
        meeting_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        latest = await java_client.request(
            "GET", f"/api/meetings/{meeting_id}/processing-tasks/latest",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
        )
        if latest.status_code != 200:
            return passthrough(latest.status_code, latest.content, x_request_id, x_trace_id)
        body = _safe_json(latest)
        task_id = ((body or {}).get("data") or {}).get("taskId")
        if not task_id:
            return error(
                status_code=409,
                code="INVALID_TASK_PHASE",
                message="no task to finalize on this meeting",
                retryable=False,
                request_id=x_request_id, trace_id=x_trace_id,
            )
        resume = await java_client.request(
            "POST", f"/api/processing-tasks/{task_id}:resume-java-phase",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(resume.status_code, resume.content, x_request_id, x_trace_id)

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
