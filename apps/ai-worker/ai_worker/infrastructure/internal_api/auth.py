from __future__ import annotations

import hashlib
import hmac
from collections import OrderedDict
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
# A nonce only needs to be remembered for the timestamp-skew window: anything
# older is already rejected by the skew check, so we evict by TTL rather than by
# a fixed count. This closes the failure mode of the previous
# ``deque(maxlen=10_000)`` — a burst of >10k distinct nonces inside the window
# used to evict still-valid entries and reopen the replay surface.
#
# NOTE: this cache is per-process. A multi-replica deployment still needs a
# shared store (e.g. Redis) so a replay can't simply be aimed at a different
# pod; wire that here when it lands.
_seen_nonces: "OrderedDict[str, float]" = OrderedDict()


def _check_and_record_nonce(nonce: str, now_epoch: float, ttl_seconds: int) -> bool:
    """Return True if ``nonce`` was already seen within its TTL (a replay).

    A constant TTL means insertion order equals expiry order, so expired entries
    are evicted from the front in amortized O(1) — no full-dict scan per call.
    """
    while _seen_nonces:
        _oldest_nonce, oldest_expiry = next(iter(_seen_nonces.items()))
        if oldest_expiry > now_epoch:
            break
        _seen_nonces.popitem(last=False)
    if nonce in _seen_nonces:
        return True
    _seen_nonces[nonce] = now_epoch + ttl_seconds
    return False


def reset_nonce_cache() -> None:
    """Clear the in-process nonce cache. Test-only."""
    _seen_nonces.clear()


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
    if _check_and_record_nonce(nonce, now.timestamp(), max_skew_seconds):
        return False
    return True
