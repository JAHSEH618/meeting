from __future__ import annotations

from ai_worker.infrastructure.internal_api.nonce_store import (
    InMemoryNonceStore,
    RedisNonceStore,
    build_nonce_store,
)


class _FakeRedis:
    """Minimal SET NX EX semantics: returns True on first set, None on replay."""

    def __init__(self) -> None:
        self.store: dict[str, str] = {}
        self.calls = 0

    def set(self, key: str, value: str, nx: bool = False, ex: int | None = None):
        self.calls += 1
        if nx and key in self.store:
            return None
        self.store[key] = value
        return True


class _BrokenRedis:
    def set(self, *args, **kwargs):
        raise RuntimeError("connection refused")


class TestInMemoryNonceStore:
    def test_replay_within_ttl_rejected(self) -> None:
        s = InMemoryNonceStore()
        assert s.check_and_record("n1", 1000.0, 300) is False
        assert s.check_and_record("n1", 1100.0, 300) is True

    def test_evicted_after_ttl(self) -> None:
        s = InMemoryNonceStore()
        assert s.check_and_record("n1", 1000.0, 300) is False
        assert s.check_and_record("n1", 1400.0, 300) is False  # > ttl → fresh again

    def test_flood_does_not_evict_unexpired(self) -> None:
        s = InMemoryNonceStore()
        assert s.check_and_record("victim", 1000.0, 300) is False
        for i in range(20_000):
            s.check_and_record(f"flood_{i}", 1000.0, 300)
        assert s.check_and_record("victim", 1000.0, 300) is True


class TestRedisNonceStore:
    def test_detects_replay_via_nx(self) -> None:
        store = RedisNonceStore(InMemoryNonceStore(), client=_FakeRedis(), key_prefix="t:")
        assert store.check_and_record("n1", 1000.0, 300) is False  # first sight
        assert store.check_and_record("n1", 1000.0, 300) is True   # replay
        assert store.check_and_record("n2", 1000.0, 300) is False  # distinct nonce ok

    def test_namespaces_keys_with_prefix(self) -> None:
        fake = _FakeRedis()
        store = RedisNonceStore(InMemoryNonceStore(), client=fake, key_prefix="ai-worker:nonce:")
        store.check_and_record("abc", 1000.0, 300)
        assert "ai-worker:nonce:abc" in fake.store

    def test_falls_back_to_in_memory_on_redis_error(self) -> None:
        # When Redis raises, the in-memory fallback must still catch the replay
        # (degrade, don't reject valid traffic and don't silently allow replays).
        store = RedisNonceStore(InMemoryNonceStore(), client=_BrokenRedis())
        assert store.check_and_record("n1", 1000.0, 300) is False
        assert store.check_and_record("n1", 1000.0, 300) is True


class TestBuildNonceStore:
    def test_defaults_to_in_memory(self) -> None:
        assert isinstance(build_nonce_store(None), InMemoryNonceStore)

    def test_empty_url_is_in_memory(self) -> None:
        assert isinstance(build_nonce_store(""), InMemoryNonceStore)
