from __future__ import annotations

import asyncio
import hmac
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Annotated, Awaitable, Callable

from typing import Literal

from fastapi import Header, Request
from pydantic import BaseModel, Field, StringConstraints

from ai_worker.common.config import settings
from ai_worker.common.hmac_signing import compute_signature
from ai_worker.infrastructure.internal_api.nonce_store import build_nonce_store

SourceType = Literal[
    "PRIMARY_TRANSCRIPT",
    "AI_SUMMARY",
    "MINUTES",
    "DECISION",
    "ACTION_ITEM",
    "RISK",
    "DOCUMENT",
]


class RerankCandidate(BaseModel):
    chunkId: str
    sourceType: SourceType
    text: Annotated[str, StringConstraints(min_length=1)]
    rrfScore: float = Field(ge=0)
    sourceVersion: int | None = None
    citationHint: dict | None = None


class RerankRequest(BaseModel):
    tenantId: str
    query: Annotated[str, StringConstraints(min_length=1)]
    candidates: list[RerankCandidate] = Field(..., min_length=1, max_length=50)
    topN: int = Field(default=8, ge=1, le=20)
    modelVersion: str


class RerankResultItem(BaseModel):
    chunkId: str
    rank: int = Field(ge=1)
    # Contract bounds rerankScore to [0, 1]; the bge reranker normalizes into
    # that range and the handler clamps before constructing this, so the bound
    # documents + guards the contract rather than ever rejecting a live score.
    rerankScore: float = Field(ge=0.0, le=1.0)


class RerankResponse(BaseModel):
    modelVersion: str
    items: list[RerankResultItem]


class EmbedRequest(BaseModel):
    tenantId: str
    texts: list[Annotated[str, StringConstraints(min_length=1)]] = Field(
        ..., min_length=1, max_length=64
    )
    modelVersion: str


class EmbedResponse(BaseModel):
    modelVersion: str
    dimension: int = Field(ge=1)
    vectors: list[list[float]]


# ── Nonce replay protection ──────────────────────────────────────────────────
# A nonce only needs to be remembered for the timestamp-skew window: anything
# older is already rejected by the skew check, so the store evicts by TTL.
# Backend is chosen by config: a shared Redis when AI_WORKER_NONCE_REDIS_URL is
# set (required for multi-replica, so a replay can't be aimed at a different
# pod), otherwise a per-process in-memory TTL cache. See nonce_store.py.
_nonce_store = build_nonce_store(settings.nonce_redis_url, settings.nonce_redis_key_prefix)


def _check_and_record_nonce(nonce: str, now_epoch: float, ttl_seconds: int) -> bool:
    """Return True if ``nonce`` was already seen within its TTL (a replay)."""
    return _nonce_store.check_and_record(nonce, now_epoch, ttl_seconds)


def reset_nonce_cache() -> None:
    """Clear the nonce cache. Test-only."""
    _nonce_store.reset()


def _signature_and_timestamp_ok(
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    nonce: str,
    signature: str,
    max_skew_seconds: int,
    now: datetime,
) -> bool:
    """Constant-time signature check + timestamp-skew check (no replay state)."""
    expected = compute_signature(
        settings.internal_api_hmac_secret, method, path, body, timestamp, nonce
    )
    if not hmac.compare_digest(expected, signature):
        return False
    try:
        ts = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return False
    return abs((now - ts).total_seconds()) <= max_skew_seconds


# Replay protection TTL: a request signed at ``ts`` stays valid for real-time in
# ``[ts - max_skew, ts + max_skew]`` (abs() skew), a 2*max_skew-wide window.
# Remember the nonce for the whole window — a TTL of only max_skew would forget a
# future-dated request's nonce while it was still signature/timestamp-valid,
# reopening the replay surface.
def verify_hmac_signature(
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    nonce: str,
    signature: str,
    max_skew_seconds: int = 300,
) -> bool:
    """Synchronous full verify (signature + timestamp + replay).

    The replay-nonce store call may block on Redis; async FastAPI handlers should
    use :func:`verify_hmac_signature_async`, which offloads that call off the
    event loop. Kept synchronous for non-async callers and tests.
    """
    now = datetime.now(timezone.utc)
    if not _signature_and_timestamp_ok(
        method, path, body, timestamp, nonce, signature, max_skew_seconds, now
    ):
        return False
    if _check_and_record_nonce(nonce, now.timestamp(), 2 * max_skew_seconds):
        return False
    return True


async def verify_hmac_signature_async(
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    nonce: str,
    signature: str,
    max_skew_seconds: int = 300,
) -> bool:
    """Async verify for FastAPI handlers.

    The (potentially blocking) Redis replay-nonce check is offloaded to a worker
    thread so a Redis hiccup can't stall the shared event loop and serialize all
    concurrent internal requests / health probes.
    """
    now = datetime.now(timezone.utc)
    if not _signature_and_timestamp_ok(
        method, path, body, timestamp, nonce, signature, max_skew_seconds, now
    ):
        return False
    loop = asyncio.get_running_loop()
    is_replay = await loop.run_in_executor(
        None, _check_and_record_nonce, nonce, now.timestamp(), 2 * max_skew_seconds
    )
    return not is_replay


def request_path_with_query(request: Request) -> str:
    """Canonical signed path: path plus the raw query string when present.

    Matches the contract's URL_PATH_WITH_QUERY and Java's verifiers, so a future
    query-bearing call (e.g. /internal/models/warmup?capabilities=...) signs and
    verifies consistently. With no query this is just ``request.url.path``.
    """
    query = request.url.query
    return f"{request.url.path}?{query}" if query else request.url.path


@dataclass
class VerifiedInternalRequest:
    """Result of a successful HMAC check, handed to the route handler."""

    body: bytes
    request_id: str
    trace_id: str
    tenant_id: str


class HmacAuthError(Exception):
    """Raised by the require_hmac dependency on a failed internal-API HMAC check.

    Carries the per-endpoint error code + correlation ids so a single app-level
    handler can render the canonical 401 envelope.
    """

    def __init__(self, code: str, request_id: str, trace_id: str) -> None:
        super().__init__(code)
        self.code = code
        self.request_id = request_id
        self.trace_id = trace_id


def require_hmac(code_prefix: str) -> Callable[..., Awaitable["VerifiedInternalRequest"]]:
    """Build a FastAPI dependency that verifies the internal-API HMAC headers.

    Collapses the six ``Header(...)`` declarations + body read + signature verify
    + 401 envelope that every internal endpoint used to repeat. ``code_prefix``
    keeps the per-endpoint error code (e.g. "EMBEDDING" → EMBEDDING_AUTH_FAILED).
    """

    async def _dependency(
        request: Request,
        x_request_id: str = Header(...),
        x_trace_id: str = Header(...),
        x_tenant_id: str = Header(...),
        x_timestamp: str = Header(...),
        x_nonce: str = Header(...),
        x_signature: str = Header(...),
    ) -> "VerifiedInternalRequest":
        body = await request.body()
        ok = await verify_hmac_signature_async(
            method=request.method,
            path=request_path_with_query(request),
            body=body,
            timestamp=x_timestamp,
            nonce=x_nonce,
            signature=x_signature,
        )
        if not ok:
            raise HmacAuthError(f"{code_prefix}_AUTH_FAILED", x_request_id, x_trace_id)
        return VerifiedInternalRequest(
            body=body,
            request_id=x_request_id,
            trace_id=x_trace_id,
            tenant_id=x_tenant_id,
        )

    return _dependency
