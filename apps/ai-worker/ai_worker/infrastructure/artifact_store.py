"""ArtifactStore port — intermediate artifact read/write to TOS/MinIO.

Implementation: volcengine-tos-python-sdk (production), minio/Moto (test).
Alternative: S3-compatible abstraction.
"""

from typing import Protocol, runtime_checkable
from dataclasses import dataclass


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
