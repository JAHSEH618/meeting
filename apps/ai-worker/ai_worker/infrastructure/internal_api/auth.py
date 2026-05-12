from __future__ import annotations

import hashlib
import hmac
import time
from datetime import datetime, timezone

from pydantic import BaseModel, Field, constr, conint

from ai_worker.common.config import settings


class RerankCandidate(BaseModel):
    chunkId: str
    sourceType: str
    text: constr(min_length=1)
    rrfScore: float
    sourceVersion: int | None = None
    citationHint: dict | None = None


class RerankRequest(BaseModel):
    tenantId: str
    query: constr(min_length=1)
    candidates: list[RerankCandidate] = Field(..., min_length=1, max_length=50)
    topN: conint(ge=1, le=20) = 8
    modelVersion: str


class RerankResultItem(BaseModel):
    chunkId: str
    rank: conint(ge=1)
    rerankScore: float


class RerankResponse(BaseModel):
    modelVersion: str
    items: list[RerankResultItem]


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
        settings.callback_hmac_secret.encode(),
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
    return True