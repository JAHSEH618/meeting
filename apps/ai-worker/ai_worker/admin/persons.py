"""/admin/persons — thin BFF passthrough to Java's /api/persons."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import passthrough, parse_json_body
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def build_persons_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin/persons", tags=["admin-persons"])

    @router.get("", status_code=200)
    async def search(
        q: str | None = None,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        response = await java_client.request(
            "GET",
            "/api/persons",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            params={"q": q} if q else {},
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("", status_code=200)
    async def create(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await parse_json_body(request)
        response = await java_client.request(
            "POST",
            "/api/persons",
            claims=claims,
            request_id=x_request_id,
            trace_id=x_trace_id,
            idempotency_key=idempotency_key,
            json=body,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    return router
