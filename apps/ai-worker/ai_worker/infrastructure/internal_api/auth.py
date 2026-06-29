from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timezone
from typing import Annotated

from typing import Literal

from pydantic import BaseModel, Field, StringConstraints

from ai_worker.common.config import settings
from ai_worker.infrastructure.internal_api.nonce_store import build_nonce_store

SourceType = Literal[
    "PRIMARY_TRANSCRIPT",
    "AI_SUMMARY",
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
    rerankScore: float


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


def verify_hmac_signature(
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    nonce: str,
    signature: str,
    max_skew_seconds: int = 300,
) -> bool:
    signing_string = f"{timestamp}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
    expected = hmac.new(
        settings.internal_api_hmac_secret.encode(),
        signing_string.encode(),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(f"hmac-sha256={expected}", signature):
        return False
    now = datetime.now(timezone.utc)
    try:
        ts = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        skew = abs((now - ts).total_seconds())
        if skew > max_skew_seconds:
            return False
    except (ValueError, TypeError):
        return False
    # Replay protection: nonce must be unique within the time-skew window.
    # A request signed at ``ts`` stays valid for real-time in
    # ``[ts - max_skew, ts + max_skew]`` (abs() skew above), a 2*max_skew-wide
    # window. Remember the nonce for the whole window — a TTL of only max_skew
    # would forget a future-dated request's nonce while it was still
    # signature/timestamp-valid, reopening the replay surface.
    if _check_and_record_nonce(nonce, now.timestamp(), 2 * max_skew_seconds):
        return False
    return True
