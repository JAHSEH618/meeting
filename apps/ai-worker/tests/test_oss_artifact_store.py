"""Unit tests for OssArtifactStore. Mocks oss2 so no real OSS is touched."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from ai_worker.infrastructure.artifact_store import LocalArtifactStore
from ai_worker.infrastructure.oss_artifact_store import OssArtifactStore, _parse_oss_uri


def _make_store(tmp_path: Path) -> tuple[OssArtifactStore, MagicMock]:
    fake_bucket = MagicMock(name="oss2.Bucket")
    with patch("ai_worker.infrastructure.oss_artifact_store.oss2.Bucket", return_value=fake_bucket):
        store = OssArtifactStore(
            endpoint="https://oss-cn-hangzhou.aliyuncs.com",
            region="cn-hangzhou",
            access_key_id="ak",
            access_key_secret="sk",
            local_writer=LocalArtifactStore(root=tmp_path / "local"),
            cache_dir=tmp_path / "cache",
        )
    # Pre-seed the bucket cache so _bucket() never reaches the real oss2.Bucket
    # constructor after the patch context exits.
    store._bucket_cache["meeting-audio-auska"] = fake_bucket
    store._bucket_cache["meeting-artifacts"] = fake_bucket
    return store, fake_bucket


def test_parse_oss_uri_rejects_non_oss_scheme() -> None:
    with pytest.raises(ValueError, match="oss://bucket/key"):
        _parse_oss_uri("https://example.com/foo")


def test_parse_oss_uri_extracts_bucket_and_key() -> None:
    assert _parse_oss_uri("oss://meeting-audio-auska/tenant/t1/meeting/m1/raw") == (
        "meeting-audio-auska",
        "tenant/t1/meeting/m1/raw",
    )


@pytest.mark.asyncio
async def test_download_reads_from_oss(tmp_path: Path) -> None:
    store, bucket = _make_store(tmp_path)
    result = MagicMock()
    result.read.return_value = b"hello-oss"
    bucket.get_object.return_value = result

    data = await store.download("oss://meeting-audio-auska/file.wav")

    bucket.get_object.assert_called_once_with("file.wav")
    assert data == b"hello-oss"


@pytest.mark.asyncio
async def test_download_json_parses_payload(tmp_path: Path) -> None:
    store, bucket = _make_store(tmp_path)
    result = MagicMock()
    result.read.return_value = json.dumps({"hello": "oss"}).encode("utf-8")
    bucket.get_object.return_value = result

    payload = await store.download_json("oss://meeting-artifacts/report.json")

    assert payload == {"hello": "oss"}


def test_local_path_caches_after_first_download(tmp_path: Path) -> None:
    store, bucket = _make_store(tmp_path)
    cache_dir = tmp_path / "cache"

    def fake_download(_key: str, dst: str) -> None:
        Path(dst).write_bytes(b"audio-bytes")

    bucket.head_object.return_value = MagicMock(content_length=len(b"audio-bytes"))
    bucket.get_object_to_file.side_effect = fake_download

    first = store.local_path("oss://meeting-audio-auska/file.wav")
    second = store.local_path("oss://meeting-audio-auska/file.wav")

    assert first == second
    assert first.read_bytes() == b"audio-bytes"
    assert first.is_relative_to(cache_dir)
    bucket.get_object_to_file.assert_called_once()
    # Both .part siblings must be cleaned up by the atomic-rename path.
    assert list(first.parent.glob("*.part")) == []


def test_local_path_rejects_short_download(tmp_path: Path) -> None:
    """If the byte stream finishes early, the cache must NOT publish a
    truncated file. The next call should still see no cached path."""
    store, bucket = _make_store(tmp_path)

    def fake_truncated_download(_key: str, dst: str) -> None:
        # Pretend OSS GetObject delivered only 4 bytes of a 16-byte object.
        Path(dst).write_bytes(b"head")

    bucket.head_object.return_value = MagicMock(content_length=16)
    bucket.get_object_to_file.side_effect = fake_truncated_download

    with pytest.raises(OSError, match="short download"):
        store.local_path("oss://meeting-audio-auska/short.wav")

    # Cache dir for the bucket exists (we mkdir up-front) but the target
    # file MUST not — otherwise the next ffprobe pass would consume garbage.
    bucket_dir = tmp_path / "cache" / "meeting-audio-auska"
    assert bucket_dir.exists()
    assert all(not p.is_file() or p.suffix == ".part" for p in bucket_dir.iterdir()) or True
    # And no .part stragglers remain to confuse the next attempt.
    assert list(bucket_dir.glob("*.part")) == []
    assert list(bucket_dir.iterdir()) == []


def test_local_path_cleans_up_partial_on_oss_error(tmp_path: Path) -> None:
    """A mid-stream OSS exception must not leave a stale .part file that
    a retry would see as a non-empty cache hit."""
    store, bucket = _make_store(tmp_path)

    def fake_failing_download(_key: str, dst: str) -> None:
        Path(dst).write_bytes(b"partial-bytes")
        raise RuntimeError("network reset")

    bucket.head_object.return_value = MagicMock(content_length=999)
    bucket.get_object_to_file.side_effect = fake_failing_download

    with pytest.raises(RuntimeError, match="network reset"):
        store.local_path("oss://meeting-audio-auska/flaky.wav")

    bucket_dir = tmp_path / "cache" / "meeting-audio-auska"
    assert list(bucket_dir.glob("*.part")) == []
    assert list(bucket_dir.iterdir()) == []


@pytest.mark.asyncio
async def test_upload_falls_back_to_local_writer(tmp_path: Path) -> None:
    store, bucket = _make_store(tmp_path)

    ref = await store.upload("meeting-artifacts", "quality.json", b"{}", "application/json")

    assert ref.uri == "oss://meeting-artifacts/quality.json"
    assert (tmp_path / "local" / "meeting-artifacts" / "quality.json").read_bytes() == b"{}"
    bucket.put_object.assert_not_called()


def test_constructor_validates_required_args(tmp_path: Path) -> None:
    local = LocalArtifactStore(root=tmp_path / "local")
    with pytest.raises(ValueError, match="endpoint"):
        OssArtifactStore("", "cn-hangzhou", "ak", "sk", local)
    with pytest.raises(ValueError, match="region"):
        OssArtifactStore("https://oss-cn-hangzhou.aliyuncs.com", "", "ak", "sk", local)
    with pytest.raises(ValueError, match="access_key"):
        OssArtifactStore("https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "", "sk", local)
