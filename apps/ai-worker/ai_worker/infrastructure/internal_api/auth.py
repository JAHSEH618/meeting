from __future__ import annotations

import hashlib
import hmac
from collections import deque
from datetime import datetime, timezone
from typing import Annotated

from typing import Literal

from pydantic import BaseModel, Field, StringConstraints

from ai_worker.common.config import settings

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
# In-memory LRU for recently-seen nonces. Sized for ~5min of traffic at
# moderate throughput. In a multi-instance deployment this must be backed
# by Redis or a distributed cache.
_MAX_NONCE_CACHE = 10_000
_seen_nonces: deque[str] = deque(maxlen=_MAX_NONCE_CACHE)


def _is_nonce_replayed(nonce: str) -> bool:
    """Return True if the nonce has been seen before."""
    if nonce in _seen_nonces:
        return True
    _seen_nonces.append(nonce)
    return False


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
    try:
        ts = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        skew = abs((datetime.now(timezone.utc) - ts).total_seconds())
        if skew > max_skew_seconds:
            return False
    except (ValueError, TypeError):
        return False
    # Replay protection: nonce must be unique within the time-skew window.
    if _is_nonce_replayed(nonce):
        return False
    return True
