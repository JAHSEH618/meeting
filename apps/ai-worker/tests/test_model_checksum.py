"""Tests for the Phase 8.4.1.b checksum helper."""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_worker.observability.model_checksum import compute_checksum


def test_returns_none_for_missing_dir(tmp_path: Path) -> None:
    assert compute_checksum(str(tmp_path / "nope")) is None


def test_returns_none_when_no_weights(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("just metadata")
    (tmp_path / "config.json").write_text("{}")
    assert compute_checksum(str(tmp_path)) is None


def test_hashes_safetensors_deterministically(tmp_path: Path) -> None:
    (tmp_path / "model.safetensors").write_bytes(b"abc")
    (tmp_path / "config.json").write_text("{}")  # ignored
    first = compute_checksum(str(tmp_path))
    second = compute_checksum(str(tmp_path))
    assert first == second
    assert first is not None
    assert first.startswith("sha256:")


def test_changes_when_weights_change(tmp_path: Path) -> None:
    (tmp_path / "model.safetensors").write_bytes(b"abc")
    before = compute_checksum(str(tmp_path))
    (tmp_path / "model.safetensors").write_bytes(b"def")
    after = compute_checksum(str(tmp_path))
    assert before != after


def test_changes_when_file_is_renamed(tmp_path: Path) -> None:
    weight = tmp_path / "model-00001.safetensors"
    weight.write_bytes(b"contents")
    before = compute_checksum(str(tmp_path))
    weight.rename(tmp_path / "model-00002.safetensors")
    after = compute_checksum(str(tmp_path))
    assert before != after


def test_multiple_shards_are_included(tmp_path: Path) -> None:
    (tmp_path / "model-00001.safetensors").write_bytes(b"shard-1")
    only_first = compute_checksum(str(tmp_path))
    (tmp_path / "model-00002.safetensors").write_bytes(b"shard-2")
    with_both = compute_checksum(str(tmp_path))
    assert only_first != with_both


@pytest.mark.parametrize("ext", [".bin", ".pt", ".pth"])
def test_recognises_other_weight_extensions(tmp_path: Path, ext: str) -> None:
    (tmp_path / f"pytorch_model{ext}").write_bytes(b"x")
    digest = compute_checksum(str(tmp_path))
    assert digest is not None
    assert digest.startswith("sha256:")
