from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from typing import Any, Protocol, runtime_checkable
from urllib.parse import urlparse

from ai_worker.common.config import settings


@dataclass
class ArtifactRef:
    uri: str  # tos://bucket/key
    sha256: str
    size_bytes: int | None = None
    content_type: str | None = None


@runtime_checkable
class ArtifactStore(Protocol):
    """Reads and writes intermediate artifacts (raw ASR JSON, diarization turns,
    embedding batches, quality reports) to TOS.

    Constraints:
    - Never write plaintext speaker embeddings to TOS
    - Always compute SHA256 on write and verify on read
    - Path pattern: tos://{bucket}/tenant/{tenantId}/meeting/{meetingId}/artifacts/{category}/{taskId}.json
    """

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        content_type: str = "application/octet-stream",
    ) -> ArtifactRef:
        """Upload artifact and return its reference."""
        ...

    async def download(self, uri: str) -> bytes:
        """Download artifact by URI. Verifies SHA256 if stored metadata available."""
        ...

    async def download_json(self, uri: str) -> dict:
        """Download and parse JSON artifact."""
        ...

    async def delete(self, uri: str) -> None:
        """Delete an artifact. Only for temporary/intermediate artifacts."""
        ...

    def local_path(self, uri: str) -> Path:
        """Return a local filesystem path the URI is materialized at.

        For ``LocalArtifactStore`` this is just ``root/bucket/key``; for
        ``TosArtifactStore`` it downloads-on-demand into a tmp cache.
        Used by pipeline steps that need real file paths (ffprobe / soundfile).
        """
        ...


class LocalArtifactStore:
    """Filesystem-backed ArtifactStore for local and test worker runs.

    The URI shape remains ``tos://bucket/key`` so pipeline outputs match the
    production contract while avoiding an SDK dependency for local smoke tests.
    """

    def __init__(self, root: str | Path | None = None) -> None:
        self.root = Path(root or settings.artifact_store_root)

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        content_type: str = "application/octet-stream",
    ) -> ArtifactRef:
        path = self._path_for(bucket, key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return ArtifactRef(
            uri=f"tos://{bucket}/{key}",
            sha256=hashlib.sha256(data).hexdigest(),
            size_bytes=len(data),
            content_type=content_type,
        )

    async def upload_json(self, bucket: str, key: str, payload: dict[str, Any]) -> ArtifactRef:
        data = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return await self.upload(bucket, key, data, "application/json")

    async def download(self, uri: str) -> bytes:
        scheme, bucket, key = _parse_artifact_uri(uri)
        if scheme == "file":
            return Path(key).read_bytes()
        if scheme == "tos":
            return self._path_for(bucket, key).read_bytes()
        raise ValueError(f"unsupported artifact uri scheme: {scheme}")

    async def download_json(self, uri: str) -> dict[str, Any]:
        return json.loads((await self.download(uri)).decode("utf-8"))

    async def delete(self, uri: str) -> None:
        scheme, bucket, key = _parse_artifact_uri(uri)
        if scheme == "file":
            path = Path(key)
        elif scheme == "tos":
            path = self._path_for(bucket, key)
        else:
            raise ValueError(f"unsupported artifact uri scheme: {scheme}")
        if path.exists():
            path.unlink()

    def local_path(self, uri: str) -> Path:
        scheme, bucket, key = _parse_artifact_uri(uri)
        if scheme == "file":
            return Path(key)
        if scheme == "tos":
            return self._path_for(bucket, key)
        raise ValueError(f"unsupported artifact uri scheme: {scheme}")

    def _path_for(self, bucket: str, key: str) -> Path:
        clean_key = key.lstrip("/")
        if ".." in Path(clean_key).parts:
            raise ValueError("artifact key must not contain parent traversal")
        return self.root / bucket / clean_key


def _parse_artifact_uri(uri: str) -> tuple[str, str, str]:
    parsed = urlparse(uri)
    if parsed.scheme == "file":
        return "file", "", parsed.path
    if parsed.scheme == "tos" and parsed.netloc and parsed.path:
        return parsed.scheme, parsed.netloc, parsed.path.lstrip("/")
    raise ValueError(f"invalid artifact uri: {uri}")


def build_artifact_store() -> "ArtifactStore":
    """Construct the artifact store appropriate for the current deploy.

    Resolves at import time using :mod:`ai_worker.common.config.settings`:

    * ``AI_WORKER_STORAGE_BACKEND=tos`` returns a :class:`TosArtifactStore`
      with a co-resident :class:`LocalArtifactStore` as the write fallback
      (see ``tos_artifact_store`` module docstring for why).
    * Any other value (or unset) returns a plain
      :class:`LocalArtifactStore`. This is the default for dev / CI / fake
      runtime so unit tests don't need TOS credentials.
    """
    from ai_worker.common.config import settings

    local = LocalArtifactStore()
    if (settings.storage_backend or "local").lower() != "tos":
        return local
    if not (settings.tos_endpoint and settings.tos_region and settings.tos_access_key_id and settings.tos_access_key_secret):
        raise RuntimeError(
            "AI_WORKER_STORAGE_BACKEND=tos requires AI_WORKER_TOS_ENDPOINT, "
            "AI_WORKER_TOS_REGION, AI_WORKER_TOS_ACCESS_KEY_ID, "
            "AI_WORKER_TOS_ACCESS_KEY_SECRET to be set."
        )
    # Lazy import keeps tos off the critical path for fake-runtime tests.
    from ai_worker.infrastructure.tos_artifact_store import TosArtifactStore

    return TosArtifactStore(
        endpoint=settings.tos_endpoint,
        region=settings.tos_region,
        access_key_id=settings.tos_access_key_id,
        access_key_secret=settings.tos_access_key_secret,
        local_writer=local,
    )
