"""Helpers for envelope-style responses."""

from __future__ import annotations

import json as _json
from typing import Any

from starlette.requests import Request
from starlette.responses import JSONResponse


class MalformedJsonBodyError(ValueError):
    """Request body is missing or not valid JSON — rendered as a 400 envelope."""


_NO_DEFAULT = object()


async def parse_json_body(request: Request, default: object = _NO_DEFAULT) -> object:
    """Read and parse the JSON body. Empty body returns ``default`` when given,
    otherwise raises MalformedJsonBodyError; invalid JSON always raises."""
    body = await request.body()
    if not body:
        if default is not _NO_DEFAULT:
            return default
        raise MalformedJsonBodyError("request body must be JSON")
    try:
        return _json.loads(body)
    except Exception as exc:
        raise MalformedJsonBodyError("request body must be valid JSON") from exc


def ok(data: Any, request_id: str | None, trace_id: str | None, status_code: int = 200) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "success": True,
            "data": data,
            "error": None,
            "requestId": request_id or "",
            "traceId": trace_id or "",
        },
    )


def error(
    *,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    request_id: str | None,
    trace_id: str | None,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    payload: dict[str, Any] = {
        "code": code,
        "message": message,
        "retryable": retryable,
    }
    if details:
        payload["details"] = details
    return JSONResponse(
        status_code=status_code,
        content={
            "success": False,
            "data": None,
            "error": payload,
            "requestId": request_id or "",
            "traceId": trace_id or "",
        },
    )


def passthrough(
    upstream_status: int,
    upstream_body: bytes,
    request_id: str | None,
    trace_id: str | None,
) -> JSONResponse:
    """Forward an upstream Java response, replacing requestId/traceId so the
    BFF's correlation IDs are what reach the browser."""
    import json
    try:
        body = json.loads(upstream_body)
    except Exception:
        body = {"success": False, "data": None, "error": {
            "code": "UPSTREAM_INVALID_RESPONSE",
            "message": "Java returned non-JSON",
            "retryable": True,
        }}
    if isinstance(body, dict):
        body["requestId"] = request_id or body.get("requestId") or ""
        body["traceId"] = trace_id or body.get("traceId") or ""
    return JSONResponse(status_code=upstream_status, content=body)
