"""Volcengine TOS-backed read implementation of the artifact store protocol.

Design boundary (enforced by IAM credentials, not by this code):

* ``download`` / ``download_json`` / ``local_path`` — call TOS GetObject /
  HeadObject. The worker's IAM subaccount is provisioned with **only**
  ``tos:GetObject`` and ``tos:HeadObject``; any attempt to PUT or DELETE
  on real TOS will fail with ``AccessDenied`` server-side. That is the
  intended fail-loud behaviour for the read-only boundary.

* ``upload`` / ``upload_json`` / ``delete`` — delegated to an underlying
  :class:`LocalArtifactStore` so worker-internal artifact bytes (quality
  reports, intermediate JSON) land on the pod's emptyDir. The returned
  URI still uses the ``tos://`` scheme so the contract pattern stays
  satisfied, but the bytes are NOT actually pushed to TOS. Today Java
  doesn't fetch worker artifact bytes back — they're metadata-only
  references. When that changes, replace this with a presigned-PUT
  flow where Java mints the upload URL and the worker streams to it
  using only the presigned signature.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import tos

from ai_worker.infrastructure.artifact_store import ArtifactRef, LocalArtifactStore


class TosArtifactStore:
    """Read-via-TOS, write-via-local artifact store. See module docstring."""

    def __init__(
        self,
        endpoint: str,
        region: str,
        access_key_id: str,
        access_key_secret: str,
        local_writer: LocalArtifactStore,
        cache_dir: str | Path | None = None,
    ) -> None:
        if not endpoint or not endpoint.strip():
            raise ValueError("TosArtifactStore: endpoint is required")
        if not region or not region.strip():
            raise ValueError("TosArtifactStore: region is required")
        if not access_key_id or not access_key_secret:
            raise ValueError("TosArtifactStore: access_key_id and access_key_secret are required")
        self._endpoint = endpoint
        self._region = region
        self._client = tos.TosClientV2(
            ak=access_key_id,
            sk=access_key_secret,
            endpoint=endpoint,
            region=region,
        )
        self._local_writer = local_writer
        self._cache_dir = Path(cache_dir) if cache_dir else Path(tempfile.gettempdir()) / "ai-worker-tos"
        self._cache_dir.mkdir(parents=True, exist_ok=True)

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        content_type: str = "application/octet-stream",
    ) -> ArtifactRef:
        # Worker-internal artifacts (quality reports, embeddings JSON) go to
        # the pod's emptyDir — IAM credentials deliberately lack PutObject.
        return await self._local_writer.upload(bucket, key, data, content_type)

    async def upload_json(self, bucket: str, key: str, payload: dict[str, Any]) -> ArtifactRef:
        return await self._local_writer.upload_json(bucket, key, payload)

    async def download(self, uri: str) -> bytes:
        bucket, key = _parse_tos_uri(uri)
        result = self._client.get_object(bucket, key)
        return result.read()

    async def download_json(self, uri: str) -> dict[str, Any]:
        return json.loads((await self.download(uri)).decode("utf-8"))

    async def delete(self, uri: str) -> None:
        # Match upload(): delete is also worker-internal-only, delegated to
        # the local writer. Real TOS DeleteObject is performed by Java's
        # compliance jobs, not by the worker.
        await self._local_writer.delete(uri)

    def local_path(self, uri: str) -> Path:
        """Download once, cache locally, return path. Used by AUDIO_PREPROCESS
        which calls ffprobe / soundfile against a real filesystem path.

        Atomicity contract: only files that completed download + size
        verification end up at the target path. A previous interrupted run
        (worker OOM, pod kill, network drop) cannot leave a half-written
        cache file that subsequent ffprobe/ASR passes would silently treat
        as a valid input — the partial bytes live under a unique
        ``.part`` sibling that gets cleaned up on failure.
        """
        bucket, key = _parse_tos_uri(uri)
        # Hash the bucket+key so we don't accidentally expose tenant paths
        # via filenames on shared scratch volumes.
        cache_name = hashlib.sha256(f"{bucket}/{key}".encode("utf-8")).hexdigest()
        target = self._cache_dir / bucket / cache_name
        if target.exists() and target.stat().st_size > 0:
            return target
        target.parent.mkdir(parents=True, exist_ok=True)

        # HEAD first to know the expected size; if the object is missing
        # this raises an exception before we waste cycles streaming to disk.
        head = self._client.head_object(bucket, key)
        expected_size = head.content_length

        # Create a unique sibling file so concurrent downloads of the same
        # URI don't trample each other and so a previous crashed worker's
        # .part stragglers don't get accidentally reused.
        fd, tmp_str = tempfile.mkstemp(
            prefix=f"{cache_name}.",
            suffix=".part",
            dir=target.parent,
        )
        os.close(fd)
        tmp = Path(tmp_str)
        try:
            self._client.get_object_to_file(bucket, key, str(tmp))
            actual_size = tmp.stat().st_size
            if expected_size is not None and actual_size != expected_size:
                raise OSError(
                    f"TosArtifactStore: short download for {uri}: "
                    f"got {actual_size} bytes, expected {expected_size}"
                )
            # Atomic publish — POSIX rename is atomic within a filesystem,
            # so any reader that observes ``target`` sees the full file.
            os.replace(tmp, target)
        except BaseException:
            # Includes KeyboardInterrupt / pod SIGTERM: never leave a
            # half-written byte stream where the next pass could find it.
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
            raise
        return target


def _parse_tos_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "tos" or not parsed.netloc or not parsed.path:
        raise ValueError(f"TosArtifactStore expects tos://bucket/key, got: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")
