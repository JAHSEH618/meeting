from __future__ import annotations

import pytest

from ai_worker.model_runtime.rerank import (
    BgeRerankerRuntime,
    BgeRerankerRuntimeError,
)


def test_fake_runtime_starts_ready() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    assert runtime.status == "READY"
    assert runtime.device == "fake"
    assert runtime.use_fake is True
    assert runtime.model_version == "bge-reranker-v2-m3-fake-v0"


@pytest.mark.asyncio
async def test_fake_runtime_ensure_loaded_is_noop() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    await runtime.ensure_loaded()
    assert runtime.status == "READY"


def test_fake_rank_is_order_preserving_descending() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    scores = runtime.rank("query", ["a", "b", "c", "d"])
    assert scores == [1.0, 0.95, 0.9, 0.85]


def test_fake_rank_floors_score_above_zero_for_long_lists() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    scores = runtime.rank("q", [f"cand-{i}" for i in range(30)])
    assert len(scores) == 30
    assert min(scores) == pytest.approx(0.05)
    # Strictly non-increasing.
    for prev, nxt in zip(scores, scores[1:]):
        assert prev >= nxt


def test_fake_rank_empty_candidates_returns_empty() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    assert runtime.rank("query", []) == []


def test_rank_empty_query_raises_contract_error() -> None:
    runtime = BgeRerankerRuntime(use_fake=True)
    with pytest.raises(BgeRerankerRuntimeError) as exc:
        runtime.rank("", ["a"])
    assert exc.value.error_code == "RERANK_INVALID_QUERY"


def test_real_runtime_starts_not_loaded() -> None:
    runtime = BgeRerankerRuntime(use_fake=False)
    assert runtime.status == "NOT_LOADED"
    assert runtime.device == "cpu"
    assert runtime.model_version == "bge-reranker-v2-m3-v1"


def test_real_runtime_rejects_rank_before_load() -> None:
    runtime = BgeRerankerRuntime(use_fake=False)
    with pytest.raises(BgeRerankerRuntimeError) as exc:
        runtime.rank("q", ["a"])
    assert exc.value.error_code == "RERANK_MODEL_NOT_READY"


@pytest.mark.asyncio
async def test_real_runtime_load_failure_marks_error_and_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = BgeRerankerRuntime(use_fake=False)

    def _boom() -> None:
        raise RuntimeError("simulated load failure")

    monkeypatch.setattr(runtime, "_load_model_blocking", _boom)

    with pytest.raises(BgeRerankerRuntimeError) as exc:
        await runtime.ensure_loaded()

    assert exc.value.error_code == "RERANK_MODEL_LOAD_FAILED"
    assert runtime.status == "ERROR"
    assert "simulated load failure" in (runtime.last_error or "")
