"""Replay-nonce stores for inbound internal-API HMAC auth.

A nonce only needs to be remembered for the timestamp-skew window: anything
older is already rejected by the skew check. Two backends:

* :class:`InMemoryNonceStore` — per-process ``OrderedDict`` with amortized O(1)
  TTL eviction. Correct for a single replica; a replay aimed at a *different*
  pod would not be seen.
* :class:`RedisNonceStore` — atomic ``SET key 1 NX EX <ttl>`` against a shared
  Redis so every replica sees the same nonces. Falls back to an in-memory store
  on any Redis error so a Redis blip degrades replay protection rather than
  rejecting otherwise-valid internal calls.
"""
from __future__ import annotations

import logging
from collections import OrderedDict
from typing import Any, Protocol

logger = logging.getLogger(__name__)


class NonceStore(Protocol):
    def check_and_record(self, nonce: str, now_epoch: float, ttl_seconds: int) -> bool:
        """Return True if ``nonce`` was already seen within its TTL (a replay)."""
        ...

    def reset(self) -> None:
        """Clear cached state. Test-only."""
        ...


class InMemoryNonceStore:
    """Per-process nonce cache with TTL eviction (insertion order == expiry order)."""

    def __init__(self) -> None:
        self._seen: "OrderedDict[str, float]" = OrderedDict()

    def check_and_record(self, nonce: str, now_epoch: float, ttl_seconds: int) -> bool:
        # A constant TTL means insertion order equals expiry order, so expired
        # entries evict from the front in amortized O(1) — no full-dict scan.
        while self._seen:
            _oldest_nonce, oldest_expiry = next(iter(self._seen.items()))
            if oldest_expiry > now_epoch:
                break
            self._seen.popitem(last=False)
        if nonce in self._seen:
            return True
        self._seen[nonce] = now_epoch + ttl_seconds
        return False

    def reset(self) -> None:
        self._seen.clear()


class RedisNonceStore:
    """Shared-Redis nonce store with in-memory fallback on Redis errors."""

    def __init__(
        self,
        fallback: NonceStore,
        *,
        url: str | None = None,
        key_prefix: str = "ai-worker:nonce:",
        client: Any | None = None,
    ) -> None:
        self._fallback = fallback
        self._key_prefix = key_prefix
        if client is not None:
            self._client = client
        elif url:
            import redis  # lazy: only needed when a Redis URL is configured

            self._client = redis.Redis.from_url(
                url,
                socket_timeout=1.0,
                socket_connect_timeout=1.0,
                health_check_interval=30,
            )
        else:
            raise ValueError("RedisNonceStore requires either a url or a client")

    def check_and_record(self, nonce: str, now_epoch: float, ttl_seconds: int) -> bool:
        key = self._key_prefix + nonce
        try:
            # NX → only sets when absent; returns truthy on first sight, None on
            # replay. EX bounds the key to the skew window so it self-expires.
            was_set = self._client.set(key, "1", nx=True, ex=ttl_seconds)
            return was_set is None
        except Exception as exc:  # noqa: BLE001 — any Redis failure degrades, never rejects
            logger.error(
                "nonce_redis_unavailable degrading to in-memory replay check: %s", exc
            )
            return self._fallback.check_and_record(nonce, now_epoch, ttl_seconds)

    def reset(self) -> None:
        self._fallback.reset()


def build_nonce_store(
    redis_url: str | None,
    key_prefix: str = "ai-worker:nonce:",
) -> NonceStore:
    """Pick the nonce store: Redis when a URL is configured, else in-memory."""
    fallback = InMemoryNonceStore()
    if redis_url:
        try:
            store = RedisNonceStore(fallback, url=redis_url, key_prefix=key_prefix)
            logger.info("nonce_store=redis url_configured=true")
            return store
        except Exception as exc:  # noqa: BLE001 — bad URL / missing client must not crash boot
            logger.error(
                "nonce_redis_init_failed using in-memory store: %s", exc
            )
            return fallback
    logger.info("nonce_store=in-memory (single-replica only)")
    return fallback
