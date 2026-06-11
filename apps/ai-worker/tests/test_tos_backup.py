import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_worker.infrastructure.artifact_store import LocalArtifactStore, _backup_to_tos_async


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


@pytest.mark.asyncio
async def test_upload_no_backup_when_disabled(tmp_path, mock_settings):
    mock_settings.enable_tos_backup = False
    store = LocalArtifactStore(root=tmp_path)
    with patch("ai_worker.infrastructure.artifact_store.asyncio.create_task") as mock_create_task:
        await store.upload("bucket", "key", b"data", "text/plain")
        mock_create_task.assert_not_called()
