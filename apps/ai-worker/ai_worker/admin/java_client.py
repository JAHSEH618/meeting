"""Java public-API proxy client used by the workstation admin BFF.

Hold no HMAC secret — the admin BFF is strictly user-scoped. It forwards the
user's JWT (already verified by us) and propagates the trace headers so
Java-side logs line up with browser → BFF → Java spans.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Mapping

import httpx

from ai_worker.admin.jwt_middleware import AdminClaims
from ai_worker.common.config import settings


class UpstreamUnavailableError(RuntimeError):
    """meeting-api is unreachable / timed out — rendered as a 502 envelope."""


class JavaPublicClient:
    """Thin wrapper around :class:`httpx.AsyncClient` for /api/* calls.

    Routes can either use the long-lived :pyattr:`client` for connection
    pooling, or build a one-shot client via :py:meth:`one_shot`. Tests
    typically replace this whole instance with a mock.
    """

    def __init__(self, base_url: str | None = None, timeout: float = 10.0) -> None:
        target = base_url or settings.java_api_base_url
        if not target:
            raise RuntimeError(
                "java_api_base_url is not configured — set AI_WORKER_JAVA_API_BASE_URL"
            )
        self._base_url = target.rstrip("/")
        self._timeout = timeout
        self._client = httpx.AsyncClient(base_url=self._base_url, timeout=timeout)

    @property
    def base_url(self) -> str:
        return self._base_url

    def _headers(
        self,
        claims: AdminClaims,
        request_id: str | None,
        trace_id: str | None,
        idempotency_key: str | None,
    ) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {claims.raw_token}",
            "X-Tenant-Id": claims.tenant_id,
        }
        if request_id:
            headers["X-Request-Id"] = request_id
        if trace_id:
            headers["X-Trace-Id"] = trace_id
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    async def request(
        self,
        method: str,
        path: str,
        *,
        claims: AdminClaims,
        request_id: str | None = None,
        trace_id: str | None = None,
        idempotency_key: str | None = None,
        json: Any | None = None,
        params: Mapping[str, Any] | None = None,
        content: bytes | None = None,
        extra_headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        headers = self._headers(claims, request_id, trace_id, idempotency_key)
        if extra_headers:
            headers.update(dict(extra_headers))
        try:
            return await self._client.request(
                method,
                path,
                headers=headers,
                json=json,
                params=params,
                content=content,
            )
        except httpx.RequestError as exc:
            raise UpstreamUnavailableError(f"meeting-api unavailable: {exc}") from exc

    async def close(self) -> None:
        await self._client.aclose()

    @asynccontextmanager
    async def one_shot(self) -> AsyncIterator[httpx.AsyncClient]:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=self._timeout) as c:
            yield c
