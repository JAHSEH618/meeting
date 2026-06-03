"""/admin/files — generic multipart upload BFF passthrough."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import error, passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def build_files_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin/files", tags=["admin-files"])

    @router.post("/uploads", status_code=200)
    async def init_upload(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        if idempotency_key is None or not idempotency_key.strip():
            return error(
                status_code=400,
                code="VALIDATION_FAILED",
                message="Idempotency-Key is required",
                retryable=False,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )
        body = await request.json()
        response = await java_client.request(
            "POST",
            "/api/files",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/parts", status_code=200)
    async def create_part(
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
            f"/api/files/{upload_id}/parts",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/complete", status_code=200)
    async def complete(
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
            f"/api/files/{upload_id}/complete",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/abort", status_code=200)
    async def abort(
        upload_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        response = await java_client.request(
            "POST",
            f"/api/files/{upload_id}/abort",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    return router
