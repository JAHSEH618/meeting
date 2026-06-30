"""D7 — production ReferenceEmbeddingSupplier that calls Java's
/internal/speakers/reference-embeddings over HMAC.

Cache + redaction:
* Short TTL in-memory cache keyed by (tenantId, sorted person_ids); default
  ≤60s so a single meeting's matching pass doesn't repeatedly decrypt the
  same centroids on Java side.
* Plaintext vectors NEVER touch structlog / print. Failure logging only
  reports counts + the SHA hash of the response payload.
* On 4xx → ``SpeakerReferenceUnavailable`` (worker matching step decides
  whether to degrade); on 5xx → bounded retry then ``SpeakerReferenceUnavailable``.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Mapping

import httpx

from ai_worker.common.config import settings
from ai_worker.common.hmac_signing import compute_signature
from ai_worker.pipeline.speaker.matcher import ReferenceEmbedding

_log = logging.getLogger(__name__)

_DEFAULT_TIMEOUT = 5.0
_DEFAULT_TTL_SECONDS = 60
_MAX_RETRIES = 3
_BASE_BACKOFF_SECONDS = 0.2


class SpeakerReferenceUnavailable(Exception):
    """Raised when the worker cannot obtain reference centroids — matcher
    must decide whether to degrade or fail-fast."""


@dataclass
class _CacheEntry:
    expires_at: float
    by_person: dict[str, ReferenceEmbedding]


class JavaSpeakerReferenceClient:
    """Outbound client to Java's /internal/speakers/reference-embeddings."""

    def __init__(
        self,
        base_url: str,
        secret: str,
        *,
        timeout: float = _DEFAULT_TIMEOUT,
        ttl_seconds: float = _DEFAULT_TTL_SECONDS,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        if not base_url:
            raise RuntimeError("base_url is required (set AI_WORKER_MEETING_API_BASE_URL)")
        if not secret or secret == "dev-internal-secret":
            # Soft check — production deploys must set a real secret. We log
            # a warning at construction time rather than hard-fail because
            # the worker boot path may run before secrets land.
            _log.warning("JavaSpeakerReferenceClient initialized with default/dev secret")
        self._base_url = base_url.rstrip("/")
        self._secret = secret
        self._timeout = timeout
        self._ttl_seconds = ttl_seconds
        self._cache: dict[tuple[str, tuple[str, ...]], _CacheEntry] = {}
        self._owned_client = http_client is None
        self._client = http_client or httpx.AsyncClient(timeout=timeout)

    async def close(self) -> None:
        if self._owned_client:
            await self._client.aclose()

    def evict_cache(self) -> None:
        """Operator hook + ``process exit`` cleanup; clears all centroids."""
        self._cache.clear()

    async def reference_embedding(
        self,
        tenant_id: str,
        participant_id: str,
        dimension: int,
    ) -> ReferenceEmbedding:
        """ReferenceEmbeddingSupplier protocol — single-id convenience wrapper."""
        result = await self.batch(tenant_id, [participant_id])
        reference = result.get(participant_id)
        if reference is None:
            raise SpeakerReferenceUnavailable(f"no centroid for participant {participant_id}")
        if dimension and len(reference.values) != dimension:
            raise SpeakerReferenceUnavailable(
                f"dimension mismatch: expected {dimension} got {len(reference.values)}"
            )
        return reference

    async def reference_embeddings(
        self, tenant_id: str, participant_ids: list[str], dimension: int
    ) -> dict[str, ReferenceEmbedding]:
        """Batched ReferenceEmbeddingSupplier fast-path used by the matcher.

        Resolves the whole authorized scope in ONE request (vs one per id).
        Missing/omitted persons are simply absent from the result (the matcher
        skips them — consistent with Java omitting un-enrolled ids); a
        dimension-mismatched centroid is dropped with a warning so a systemic
        mismatch is observable without failing the whole matching step.
        """
        result = await self.batch(tenant_id, list(participant_ids))
        if not dimension:
            return result
        out: dict[str, ReferenceEmbedding] = {}
        for pid, ref in result.items():
            if len(ref.values) == dimension:
                out[pid] = ref
            else:
                _log.warning(
                    "speaker_reference_dim_mismatch participant=%s expected=%d got=%d",
                    pid, dimension, len(ref.values),
                )
        return out

    async def batch(self, tenant_id: str, person_ids: list[str]) -> dict[str, ReferenceEmbedding]:
        if not person_ids:
            return {}
        key = (tenant_id, tuple(sorted(set(person_ids))))
        cached = self._cache.get(key)
        if cached and cached.expires_at > time.time():
            return {
                pid: _copy_reference(ref)
                for pid, ref in cached.by_person.items()
                if pid in person_ids
            }

        last_exc: Exception | None = None
        for attempt in range(_MAX_RETRIES):
            try:
                payload = await self._call(tenant_id, list(key[1]))
                self._cache[key] = _CacheEntry(
                    expires_at=time.time() + self._ttl_seconds,
                    by_person=payload,
                )
                return {
                    pid: _copy_reference(ref)
                    for pid, ref in payload.items()
                    if pid in person_ids
                }
            except _Retryable as exc:
                last_exc = exc
                wait = _BASE_BACKOFF_SECONDS * (2 ** attempt)
                _log.warning(
                    "speaker_reference_retry attempt=%d wait=%.2f reason=%s",
                    attempt + 1, wait, exc,
                )
                await asyncio.sleep(wait)
            except SpeakerReferenceUnavailable:
                raise
        raise SpeakerReferenceUnavailable(
            f"speaker reference request failed after {_MAX_RETRIES} attempts: {last_exc}"
        )

    async def _call(
        self, tenant_id: str, person_ids: list[str]
    ) -> dict[str, ReferenceEmbedding]:
        import json
        body_dict = {"tenantId": tenant_id, "personIds": person_ids}
        body_bytes = json.dumps(body_dict, separators=(",", ":")).encode("utf-8")
        timestamp = _utc_iso_now()
        nonce = uuid.uuid4().hex
        # Worker path INCLUDES /internal — the signing string must mirror Java's view.
        path = "/internal/speakers/reference-embeddings"
        url = self._base_url + path
        signature = _sign(self._secret, "POST", path, body_bytes, timestamp, nonce)
        headers: Mapping[str, str] = {
            "Content-Type": "application/json",
            "X-Request-Id": uuid.uuid4().hex,
            "X-Trace-Id": uuid.uuid4().hex,
            "X-Tenant-Id": tenant_id,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": signature,
        }
        response = await self._client.post(url, headers=dict(headers), content=body_bytes)
        if response.status_code == 401:
            raise SpeakerReferenceUnavailable("HMAC rejected by Java (401)")
        if 500 <= response.status_code < 600:
            raise _Retryable(f"server error {response.status_code}")
        if response.status_code >= 400:
            raise SpeakerReferenceUnavailable(
                f"Java rejected request: {response.status_code}"
            )
        envelope = response.json()
        if not envelope.get("success"):
            err = (envelope.get("error") or {}).get("code", "UNKNOWN")
            raise SpeakerReferenceUnavailable(f"Java envelope error: {err}")
        items = (envelope.get("data") or {}).get("items") or []
        by_person: dict[str, ReferenceEmbedding] = {}
        for item in items:
            person_id = item.get("personId")
            speaker_profile_id = item.get("speakerProfileId")
            values = item.get("values")
            if (
                isinstance(person_id, str)
                and isinstance(speaker_profile_id, str)
                and isinstance(values, list)
            ):
                by_person[person_id] = ReferenceEmbedding(
                    person_id=person_id,
                    speaker_profile_id=speaker_profile_id,
                    values=[float(x) for x in values],
                )
            elif isinstance(person_id, str):
                raise SpeakerReferenceUnavailable(
                    f"Java reference item missing speakerProfileId for participant {person_id}"
                )
        # Hash for debug logs without leaking plaintext.
        person_hash = hashlib.sha256(",".join(sorted(by_person.keys())).encode()).hexdigest()[:12]
        _log.info(
            "speaker_reference_resolved tenant=%s requested=%d resolved=%d hash=%s",
            tenant_id, len(person_ids), len(by_person), person_hash,
        )
        return by_person


class _Retryable(Exception):
    pass


def _sign(secret: str, method: str, path: str, body: bytes, timestamp: str, nonce: str) -> str:
    return compute_signature(secret, method, path, body, timestamp, nonce)


def _utc_iso_now() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _copy_reference(reference: ReferenceEmbedding) -> ReferenceEmbedding:
    return ReferenceEmbedding(
        person_id=reference.person_id,
        speaker_profile_id=reference.speaker_profile_id,
        values=list(reference.values),
    )


def build_default_client() -> JavaSpeakerReferenceClient:
    base = settings.meeting_api_base_url
    secret = settings.internal_api_hmac_secret
    return JavaSpeakerReferenceClient(base_url=base, secret=secret)


__all__ = [
    "JavaSpeakerReferenceClient",
    "SpeakerReferenceUnavailable",
    "build_default_client",
]
