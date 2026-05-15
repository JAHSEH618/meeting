from __future__ import annotations

import math

import pytest

from ai_worker.model_runtime.embedding import BgeM3Runtime, BgeM3RuntimeError


def _l2_norm(v: list[float]) -> float:
    return math.sqrt(sum(x * x for x in v))


def test_fake_runtime_starts_ready() -> None:
    runtime = BgeM3Runtime(use_fake=True)
    assert runtime.status == "READY"
    assert runtime.device == "fake"
    assert runtime.use_fake is True
    assert runtime.model_version == "bge-m3-fake-v0"
    assert runtime.last_error is None


@pytest.mark.asyncio
async def test_fake_runtime_ensure_loaded_is_noop() -> None:
    runtime = BgeM3Runtime(use_fake=True)
    await runtime.ensure_loaded()
    await runtime.ensure_loaded()
    assert runtime.status == "READY"


def test_fake_embed_returns_1024_dim_l2_normalized_vector() -> None:
    runtime = BgeM3Runtime(use_fake=True)

    vectors = runtime.embed(["这是一个测试文本"])

    assert len(vectors) == 1
    assert len(vectors[0]) == 1024
    assert abs(_l2_norm(vectors[0]) - 1.0) < 1e-9


def test_fake_embed_is_deterministic_across_calls() -> None:
    runtime = BgeM3Runtime(use_fake=True)

    a = runtime.embed(["同样的句子"])
    b = runtime.embed(["同样的句子"])

    assert a == b


def test_fake_embed_different_texts_produce_different_vectors() -> None:
    runtime = BgeM3Runtime(use_fake=True)

    vectors = runtime.embed(["第一段文字", "完全不同的内容"])

    assert vectors[0] != vectors[1]
    # Cosine similarity should be far from 1.0 for unrelated inputs.
    dot = sum(a * b for a, b in zip(vectors[0], vectors[1]))
    assert abs(dot) < 0.5


def test_fake_embed_batch_preserves_order_and_length() -> None:
    runtime = BgeM3Runtime(use_fake=True)
    texts = [f"chunk-{i}" for i in range(40)]

    vectors = runtime.embed(texts)

    assert len(vectors) == 40
    # Order preservation: re-embedding one text gives the same vector as
    # the matching position in the batch.
    assert runtime.embed([texts[7]])[0] == vectors[7]


def test_fake_embed_empty_input_returns_empty_output() -> None:
    runtime = BgeM3Runtime(use_fake=True)
    assert runtime.embed([]) == []


def test_real_runtime_starts_not_loaded() -> None:
    runtime = BgeM3Runtime(use_fake=False)
    assert runtime.status == "NOT_LOADED"
    assert runtime.device == "cpu"
    assert runtime.use_fake is False
    assert runtime.model_version == "bge-m3-v1"


def test_real_runtime_rejects_embed_before_load() -> None:
    runtime = BgeM3Runtime(use_fake=False)

    with pytest.raises(BgeM3RuntimeError) as exc:
        runtime.embed(["text"])

    assert exc.value.error_code == "EMBEDDING_MODEL_NOT_READY"


@pytest.mark.asyncio
async def test_real_runtime_load_failure_marks_error_and_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Inject a load failure so the test is independent of whether
    # FlagEmbedding is actually installed or reachable.
    runtime = BgeM3Runtime(use_fake=False)

    def _boom() -> None:
        raise RuntimeError("simulated load failure")

    monkeypatch.setattr(runtime, "_load_model_blocking", _boom)

    with pytest.raises(BgeM3RuntimeError) as exc:
        await runtime.ensure_loaded()

    assert exc.value.error_code == "EMBEDDING_MODEL_LOAD_FAILED"
    assert runtime.status == "ERROR"
    assert "simulated load failure" in (runtime.last_error or "")


@pytest.mark.asyncio
async def test_real_runtime_concurrent_ensure_loaded_loads_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = BgeM3Runtime(use_fake=False)
    call_count = 0

    def _fake_load() -> None:
        nonlocal call_count
        call_count += 1
        runtime._model = object()  # type: ignore[attr-defined]

    monkeypatch.setattr(runtime, "_load_model_blocking", _fake_load)

    # Five concurrent calls must serialize behind the lock and trigger
    # exactly one load.
    import asyncio as _asyncio

    await _asyncio.gather(*(runtime.ensure_loaded() for _ in range(5)))

    assert call_count == 1
    assert runtime.status == "READY"
