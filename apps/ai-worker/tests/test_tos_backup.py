import hashlib
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import ai_worker.infrastructure.artifact_store as artifact_store_module
from ai_worker.infrastructure.artifact_store import (
    LocalArtifactStore,
    _backup_to_tos_async,
    aclose_backup_store,
)


@pytest.fixture(autouse=True)
def _reset_shared_backup_store():
    # The backup store is a module-level shared client — isolate tests from
    # each other (and from whatever settings they were constructed under).
    artifact_store_module._shared_backup_store = None
    yield
    artifact_store_module._shared_backup_store = None


@pytest.fixture
def mock_settings():
    with patch("ai_worker.infrastructure.artifact_store.settings") as mock:
        mock.storage_backend = "tos"
        mock.tos_endpoint = "http://test.endpoint"
        mock.tos_region = "test-region"
        mock.tos_access_key_id = "test-key"
        mock.tos_access_key_secret = "test-secret"
        mock.enable_tos_backup = True
        yield mock


@pytest.mark.asyncio
async def test_backup_skipped_when_backend_not_tos(mock_settings):
    mock_settings.storage_backend = "local"
    await _backup_to_tos_async("bucket", "key", b"data", "text/plain")


@pytest.mark.asyncio
async def test_backup_skipped_when_credentials_missing(mock_settings):
    mock_settings.tos_access_key_id = None
    await _backup_to_tos_async("bucket", "key", b"data", "text/plain")


@pytest.mark.asyncio
async def test_backup_success(mock_settings):
    with patch("ai_worker.infrastructure.tos_artifact_store.TosArtifactStore") as mock_tos:
        mock_instance = MagicMock()
        mock_instance.upload_direct = AsyncMock()
        mock_tos.return_value = mock_instance
        await _backup_to_tos_async("bucket", "key", b"data", "text/plain")
        mock_instance.upload_direct.assert_called_once_with("bucket", "key", b"data", "text/plain")


@pytest.mark.asyncio
async def test_backup_reuses_one_shared_tos_client(mock_settings):
    # A fresh TosClientV2 per artifact leaked sockets/FDs — every backup must
    # go through ONE lazily created shared store.
    with patch("ai_worker.infrastructure.tos_artifact_store.TosArtifactStore") as mock_tos:
        mock_instance = MagicMock()
        mock_instance.upload_direct = AsyncMock()
        mock_tos.return_value = mock_instance
        await _backup_to_tos_async("bucket", "key1", b"data1", "text/plain")
        await _backup_to_tos_async("bucket", "key2", b"data2", "text/plain")
        assert mock_tos.call_count == 1
        assert mock_instance.upload_direct.await_count == 2


@pytest.mark.asyncio
async def test_aclose_backup_store_closes_shared_client(mock_settings):
    with patch("ai_worker.infrastructure.tos_artifact_store.TosArtifactStore") as mock_tos:
        mock_instance = MagicMock()
        mock_instance.upload_direct = AsyncMock()
        mock_instance.aclose = AsyncMock()
        mock_tos.return_value = mock_instance
        await _backup_to_tos_async("bucket", "key", b"data", "text/plain")

        await aclose_backup_store()

        mock_instance.aclose.assert_awaited_once()
        assert artifact_store_module._shared_backup_store is None
        # Idempotent + safe when nothing was ever created.
        await aclose_backup_store()
        mock_instance.aclose.assert_awaited_once()


@pytest.mark.asyncio
async def test_backup_handles_exception(mock_settings):
    with patch("ai_worker.infrastructure.tos_artifact_store.TosArtifactStore") as mock_tos:
        mock_instance = MagicMock()
        mock_instance.upload_direct = AsyncMock(side_effect=Exception("upload failed"))
        mock_tos.return_value = mock_instance
        await _backup_to_tos_async("bucket", "key", b"data", "text/plain")


@pytest.mark.asyncio
async def test_upload_triggers_backup_task(tmp_path, mock_settings):
    store = LocalArtifactStore(root=tmp_path)
    with patch("ai_worker.infrastructure.artifact_store.asyncio.create_task") as mock_create_task:
        await store.upload("bucket", "key", b"data", "text/plain")
        mock_create_task.assert_called_once()
        # The mocked create_task captured the coroutine without scheduling it;
        # close it so GC doesn't emit "coroutine was never awaited".
        (coro,) = mock_create_task.call_args.args
        assert coro.__name__ == "_backup_to_tos_async"
        coro.close()


@pytest.mark.asyncio
async def test_upload_no_backup_when_disabled(tmp_path, mock_settings):
    mock_settings.enable_tos_backup = False
    store = LocalArtifactStore(root=tmp_path)
    with patch("ai_worker.infrastructure.artifact_store.asyncio.create_task") as mock_create_task:
        await store.upload("bucket", "key", b"data", "text/plain")
        mock_create_task.assert_not_called()


# ── TosArtifactStore source-audio cache eviction ─────────────────────────────


def test_evict_local_copy_removes_cached_download(tmp_path: Path):
    from ai_worker.infrastructure.tos_artifact_store import TosArtifactStore

    store = TosArtifactStore(
        endpoint="http://test.endpoint",
        region="test-region",
        access_key_id="test-key",
        access_key_secret="test-secret",
        local_writer=LocalArtifactStore(root=tmp_path / "local"),
        cache_dir=tmp_path / "cache",
    )
    # Seed the cache exactly where local_path would have published it.
    cache_name = hashlib.sha256(b"bucket/audio/task.wav").hexdigest()
    target = tmp_path / "cache" / "bucket" / cache_name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"pcm")

    store.evict_local_copy("tos://bucket/audio/task.wav")

    assert not target.exists()
    # Missing file → no-op, no raise.
    store.evict_local_copy("tos://bucket/audio/task.wav")
