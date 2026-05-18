"""Compute a stable checksum for a model directory (Phase 8.4.1.b).

Returns a SHA-256 hex string covering every weight file in the
directory in deterministic order, or ``None`` when the directory is
absent or empty. We hash the file contents (not their mtime / inode) so
re-extracting the same archive on a fresh node yields the same value.
"""

from __future__ import annotations

import hashlib
import logging
from pathlib import Path
from typing import Iterable

logger = logging.getLogger(__name__)

# Extensions we treat as model weight payloads. Anything else
# (tokenizer.json, config.json, README.md) is part of the published
# config rather than the weights we care about pinning.
WEIGHT_SUFFIXES = (".safetensors", ".bin", ".pt", ".pth", ".gguf", ".onnx")


def compute_checksum(models_dir: str | None) -> str | None:
    if not models_dir:
        return None
    root = Path(models_dir)
    if not root.exists() or not root.is_dir():
        logger.warning("checksum_skip_no_dir path=%s", models_dir)
        return None
    weight_files = _weight_files(root)
    if not weight_files:
        logger.warning("checksum_skip_no_weights path=%s", models_dir)
        return None
    digest = hashlib.sha256()
    for path in weight_files:
        # Include the relative file name in the digest so renames don't
        # silently keep the same checksum.
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\x00")
        with path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def _weight_files(root: Path) -> Iterable[Path]:
    return sorted(
        p for p in root.rglob("*")
        if p.is_file() and p.suffix.lower() in WEIGHT_SUFFIXES
    )
