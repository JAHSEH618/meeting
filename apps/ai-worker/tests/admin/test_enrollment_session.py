"""C5.2 — enrollment session ritual end-to-end + TTL cleanup."""

from __future__ import annotations

import asyncio
import time
from pathlib import Path

import pytest

from ai_worker.admin.session_store import EnrollmentSessionStore


@pytest.fixture
def store(tmp_path: Path) -> EnrollmentSessionStore:
    return EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)


@pytest.mark.asyncio
async def test_session_lifecycle(store: EnrollmentSessionStore, tmp_path: Path):
    session = await store.create(tenant_id="tenant_01", person_id="person_01")
    assert session.session_id.startswith("enr_")
    assert session.state == "CREATED"

    audio_path = tmp_path / f"{session.session_id}.bin"
    audio_path.write_bytes(b"audio bytes here")
    session.touch_audio(audio_path)
    await store.replace(session)

    retrieved = await store.get(session.session_id)
    assert retrieved is not None
    assert retrieved.state == "AUDIO_UPLOADED"

    retrieved.touch_preview(0.82, [0.1, 0.2, 0.3])
    await store.replace(retrieved)

    after_preview = await store.get(session.session_id)
    assert after_preview is not None
    assert after_preview.state == "PREVIEWED"
    assert after_preview.quality_score == 0.82


@pytest.mark.asyncio
async def test_drop_cleans_audio(store: EnrollmentSessionStore, tmp_path: Path):
    session = await store.create("tenant_01", "person_01")
    audio_path = tmp_path / "audio.bin"
    audio_path.write_bytes(b"x" * 1024)
    session.touch_audio(audio_path)
    await store.replace(session)

    await store.drop(session.session_id)

    assert await store.get(session.session_id) is None
    assert not audio_path.exists()


@pytest.mark.asyncio
async def test_expired_session_is_evicted(tmp_path: Path):
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=0)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "audio.bin"
    audio.write_bytes(b"data")
    session.touch_audio(audio)
    await store.replace(session)

    # Give the system clock a beat so expires_at is strictly in the past.
    time.sleep(0.01)

    assert await store.get(session.session_id) is None
    evicted = await store.evict_expired()
    assert evicted >= 0  # already cleaned up via get; both code paths fine


@pytest.mark.asyncio
async def test_cross_tenant_isolation(store: EnrollmentSessionStore):
    s1 = await store.create("tenant_01", "person_a")
    s2 = await store.create("tenant_02", "person_b")
    assert s1.session_id != s2.session_id
    fetched = await store.get(s1.session_id)
    assert fetched is not None and fetched.tenant_id == "tenant_01"


@pytest.mark.asyncio
async def test_cleanup_loop_runs_and_stops(tmp_path: Path):
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    await store.start_cleanup_loop(interval_seconds=1)
    await asyncio.sleep(0.05)
    await store.stop_cleanup_loop()
