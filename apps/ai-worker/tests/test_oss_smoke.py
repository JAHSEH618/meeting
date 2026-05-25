"""End-to-end smoke against real Aliyun OSS. Skipped unless both the
meeting-api (read+write) and ai-worker (read-only) RAM credentials are
present in the environment, so CI / dev machines without credentials
are unaffected.

Run locally::

    export OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
    export OSS_REGION=cn-hangzhou
    export OSS_ACCESS_KEY_ID=<meeting-api RAM AK>
    export OSS_ACCESS_KEY_SECRET=<meeting-api RAM SK>
    export AI_WORKER_OSS_ACCESS_KEY_ID=<worker RAM AK>
    export AI_WORKER_OSS_ACCESS_KEY_SECRET=<worker RAM SK>
    export STORAGE_BUCKET_AUDIO=meeting-audio-auska   # optional override
    cd apps/ai-worker && uv run pytest tests/test_oss_smoke.py -v

The two test cases that actually need OSS connectivity:

* ``test_worker_reads_object_written_by_meeting_api`` — proves the
  read boundary works: meeting-api credentials write a fixture, worker
  credentials download it through :class:`OssArtifactStore`.
* ``test_worker_ram_cannot_put_object`` — proves the IAM boundary
  works: worker credentials must be denied ``PutObject`` server-side.
  If this test fails (i.e. PutObject *succeeds* with worker
  credentials), the worker RAM policy is too broad — fix it before
  deploying.
"""

from __future__ import annotations

import os
import uuid
from pathlib import Path

import pytest

oss2 = pytest.importorskip("oss2")

from ai_worker.infrastructure.artifact_store import LocalArtifactStore  # noqa: E402
from ai_worker.infrastructure.oss_artifact_store import OssArtifactStore  # noqa: E402


_WRITER_AK = os.environ.get("OSS_ACCESS_KEY_ID")
_WRITER_SK = os.environ.get("OSS_ACCESS_KEY_SECRET")
_WORKER_AK = os.environ.get("AI_WORKER_OSS_ACCESS_KEY_ID")
_WORKER_SK = os.environ.get("AI_WORKER_OSS_ACCESS_KEY_SECRET")
_ENDPOINT = os.environ.get("OSS_ENDPOINT") or os.environ.get("AI_WORKER_OSS_ENDPOINT")
_REGION = (
    os.environ.get("OSS_REGION")
    or os.environ.get("AI_WORKER_OSS_REGION")
    or "cn-hangzhou"
)
_BUCKET = os.environ.get("STORAGE_BUCKET_AUDIO", "meeting-audio-auska")


pytestmark = pytest.mark.skipif(
    not all([_WRITER_AK, _WRITER_SK, _WORKER_AK, _WORKER_SK, _ENDPOINT]),
    reason=(
        "OSS smoke disabled: set OSS_ACCESS_KEY_ID + OSS_ACCESS_KEY_SECRET (writer) "
        "and AI_WORKER_OSS_ACCESS_KEY_ID + AI_WORKER_OSS_ACCESS_KEY_SECRET (worker) "
        "and OSS_ENDPOINT to enable."
    ),
)


@pytest.fixture
def writer_bucket() -> "oss2.Bucket":  # type: ignore[name-defined]
    """OSS bucket client wielding the meeting-api (read+write) AK/SK."""
    auth = oss2.AuthV4(_WRITER_AK, _WRITER_SK)
    return oss2.Bucket(auth, _ENDPOINT, _BUCKET, region=_REGION)


@pytest.fixture
def worker_store(tmp_path: Path) -> OssArtifactStore:
    """OssArtifactStore wired with the worker (read-only) AK/SK."""
    return OssArtifactStore(
        endpoint=_ENDPOINT,
        region=_REGION,
        access_key_id=_WORKER_AK,
        access_key_secret=_WORKER_SK,
        local_writer=LocalArtifactStore(root=tmp_path / "local"),
        cache_dir=tmp_path / "cache",
    )


@pytest.mark.asyncio
async def test_worker_reads_object_written_by_meeting_api(
    writer_bucket: "oss2.Bucket",  # type: ignore[name-defined]
    worker_store: OssArtifactStore,
) -> None:
    """Read boundary: worker AK/SK can download what meeting-api wrote."""
    key = f"smoke/test_oss_smoke/{uuid.uuid4().hex}.bin"
    payload = f"smoke-bytes-{uuid.uuid4().hex}".encode("utf-8")
    writer_bucket.put_object(key, payload)
    try:
        uri = f"oss://{_BUCKET}/{key}"
        assert await worker_store.download(uri) == payload
        cached = worker_store.local_path(uri)
        assert cached.read_bytes() == payload
        # Second call must reuse the cache (no extra GetObject network hit).
        assert worker_store.local_path(uri) == cached
    finally:
        writer_bucket.delete_object(key)


def test_worker_ram_cannot_put_object() -> None:
    """IAM boundary: worker AK/SK must be denied PutObject server-side."""
    worker_auth = oss2.AuthV4(_WORKER_AK, _WORKER_SK)
    worker_bucket = oss2.Bucket(worker_auth, _ENDPOINT, _BUCKET, region=_REGION)
    key = f"smoke/boundary/{uuid.uuid4().hex}.bin"
    with pytest.raises(oss2.exceptions.OssError) as exc_info:
        worker_bucket.put_object(key, b"worker-should-not-write")
    # Aliyun returns 403 AccessDenied when the RAM policy lacks oss:PutObject.
    # Some setups also return 401 (NoSuchAccessKeyId) if the worker AK was
    # accidentally not provisioned — both are acceptable failures here.
    assert exc_info.value.status in (401, 403), (
        f"Worker RAM produced unexpected status {exc_info.value.status}; "
        "expected 401/403 AccessDenied. Tighten the RAM policy if PutObject "
        "was actually allowed."
    )
