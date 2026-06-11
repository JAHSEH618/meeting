"""Unit tests for LocalArtifactStore - verifies local filesystem storage works correctly."""

import json
from pathlib import Path

import pytest

from ai_worker.infrastructure.artifact_store import LocalArtifactStore, ArtifactRef


@pytest.mark.asyncio
async def test_upload_and_download(tmp_path: Path):
    """Test basic upload and download of binary data."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    key = "test/file.bin"
    data = b"test data content"

    # Upload
    ref = await store.upload(bucket, key, data, "application/octet-stream")
    
    assert ref.uri == f"tos://{bucket}/{key}"
    assert ref.size_bytes == len(data)
    assert ref.sha256 is not None
    
    # Download
    downloaded = await store.download(ref.uri)
    assert downloaded == data


@pytest.mark.asyncio
async def test_upload_json_and_download_json(tmp_path: Path):
    """Test JSON upload and download with proper encoding."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    key = "test/data.json"
    payload = {"key": "value", "number": 42, "list": [1, 2, 3]}

    # Upload JSON
    ref = await store.upload_json(bucket, key, payload)
    
    assert ref.uri == f"tos://{bucket}/{key}"
    assert ref.content_type == "application/json"
    
    # Download JSON
    downloaded = await store.download_json(ref.uri)
    assert downloaded == payload


@pytest.mark.asyncio
async def test_local_path(tmp_path: Path):
    """Test local_path returns correct filesystem path."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    key = "test/file.txt"
    data = b"test"

    # Upload
    ref = await store.upload(bucket, key, data)
    
    # Get local path
    local_path = store.local_path(ref.uri)
    assert local_path == tmp_path / bucket / key
    assert local_path.exists()
    assert local_path.read_bytes() == data


@pytest.mark.asyncio
async def test_delete(tmp_path: Path):
    """Test file deletion."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    key = "test/temp.txt"
    data = b"temporary data"

    # Upload
    ref = await store.upload(bucket, key, data)
    local_path = store.local_path(ref.uri)
    assert local_path.exists()
    
    # Delete
    await store.delete(ref.uri)
    
    # Verify deleted
    assert not local_path.exists()


@pytest.mark.asyncio
async def test_file_scheme_uri(tmp_path: Path):
    """Test support for file:// URIs."""
    store = LocalArtifactStore(root=tmp_path)
    
    # Create a file directly
    test_file = tmp_path / "direct.txt"
    test_data = b"direct file content"
    test_file.write_bytes(test_data)
    
    # Download using file:// URI
    uri = f"file://{test_file}"
    downloaded = await store.download(uri)
    assert downloaded == test_data


@pytest.mark.asyncio
async def test_path_traversal_protection(tmp_path: Path):
    """Test that parent directory traversal is blocked."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    
    # Try to use .. in key
    with pytest.raises(ValueError, match="parent traversal"):
        await store.upload(bucket, "../../../etc/passwd", b"hack")


@pytest.mark.asyncio
async def test_creates_parent_directories(tmp_path: Path):
    """Test that nested directories are created automatically."""
    store = LocalArtifactStore(root=tmp_path)
    bucket = "test-bucket"
    key = "deep/nested/path/file.txt"
    data = b"nested content"

    # Upload to deep path
    ref = await store.upload(bucket, key, data)
    
    # Verify created
    local_path = store.local_path(ref.uri)
    assert local_path.exists()
    assert local_path.read_bytes() == data
