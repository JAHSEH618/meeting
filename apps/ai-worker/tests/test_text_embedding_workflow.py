from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.application.workflows.text_embedding import (
    TextEmbeddingWorkflow,
    is_embedding_task,
    to_callback_items,
)
from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.model_runtime.embedding.bge_m3_runtime import BgeM3Runtime


@pytest.fixture
def fake_runtime() -> BgeM3Runtime:
    return BgeM3Runtime(use_fake=True, device="fake")


@pytest.fixture
def state_store() -> InMemoryWorkflowStateStore:
    return InMemoryWorkflowStateStore()


def _embed_task(*, task_id: str = "task_emb_01", chunks: list[dict] | None = None,
                document_id: str | None = None, meeting_id: str | None = None) -> TaskMessage:
    return TaskMessage(
        task_id=task_id,
        task_type="TEXT_EMBEDDING",
        tenant_id="tenant_01",
        security_level="INTERNAL",
        attempt_no=1,
        pipeline_steps=("RAG_INDEXING",),
        expected_input_version={
            "chunkStrategyVersion": "default-zh-v1",
            "embeddingModelVersion": "bge-m3-v1",
        },
        trace_id=f"trace_{task_id}",
        meeting_id=meeting_id,
        document_id=document_id,
        options={"enableRagIndexing": True, "chunks": chunks or []},
    )


def _chunks(*pairs: tuple[str, str]) -> list[dict]:
    return [{"id": cid, "content": content} for cid, content in pairs]


# ── Workflow unit tests ────────────────────────────────────────


@pytest.mark.asyncio
async def test_workflow_embeds_each_chunk_with_fake_runtime(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    task = _embed_task(
        meeting_id="mtg_01",
        chunks=_chunks(("c1", "第一段会议内容"), ("c2", "第二段会议内容")),
    )

    artifact, embeddings, model_version = await workflow.run_pipeline(task)

    assert artifact.terminal_status == "SUCCEEDED"
    assert artifact.transcript_segments == []
    assert artifact.artifact_manifest_id is None

    assert len(embeddings) == 2
    assert {e.chunk_id for e in embeddings} == {"c1", "c2"}
    assert all(e.dimension == 1024 for e in embeddings)
    assert model_version == fake_runtime.model_version

    # fake runtime is deterministic — same input must produce the same vector
    workflow2 = TextEmbeddingWorkflow(InMemoryWorkflowStateStore(),
                                       BgeM3Runtime(use_fake=True, device="fake"))
    task2 = _embed_task(
        task_id="task_emb_02",
        meeting_id="mtg_01",
        chunks=_chunks(("c1", "第一段会议内容")),
    )
    _, embeddings2, _ = await workflow2.run_pipeline(task2)
    assert embeddings2[0].values == embeddings[0].values


@pytest.mark.asyncio
async def test_workflow_rejects_empty_chunks(state_store, fake_runtime) -> None:
    from ai_worker.application.workflows.audio_pipeline import WorkerPipelineError

    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    task = _embed_task(meeting_id="mtg_01", chunks=[])

    with pytest.raises(WorkerPipelineError) as ex:
        await workflow.run_pipeline(task)
    assert ex.value.error_code == "TEXT_EMBEDDING_NO_CHUNKS"
    assert ex.value.step_name == "RAG_INDEXING"
    assert ex.value.retryable is False


@pytest.mark.asyncio
async def test_workflow_skips_unknown_step(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    task = TaskMessage(
        task_id="task_unknown",
        task_type="TEXT_EMBEDDING",
        tenant_id="tenant_01",
        security_level="INTERNAL",
        attempt_no=1,
        pipeline_steps=("RAG_INDEXING", "UNKNOWN_STEP"),
        expected_input_version={"chunkStrategyVersion": "default-zh-v1"},
        meeting_id="mtg_01",
        options={"chunks": _chunks(("c1", "hello"))},
    )

    context = workflow.start_pipeline(task)
    await workflow.run_step(context, "RAG_INDEXING")
    await workflow.run_step(context, "UNKNOWN_STEP")

    assert len(context.embeddings) == 1
    assert context.skipped_steps == [{"stepName": "UNKNOWN_STEP", "reason": "OUT_OF_TEXT_EMBEDDING_SCOPE"}]


@pytest.mark.asyncio
async def test_workflow_filters_malformed_chunks(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    task = _embed_task(
        meeting_id="mtg_01",
        chunks=[
            {"id": "c1", "content": "valid"},
            {"id": "", "content": "blank id"},          # filtered
            {"id": "c3", "content": ""},                 # filtered
            {"id": "c4"},                                # no content, filtered
            {"id": "c5", "content": "still valid"},
        ],
    )

    _, embeddings, _ = await workflow.run_pipeline(task)

    assert [e.chunk_id for e in embeddings] == ["c1", "c5"]


def test_is_embedding_task_recognises_both_task_types() -> None:
    assert is_embedding_task(_embed_task(meeting_id="m"))
    rag_task = _embed_task(meeting_id="m")
    rag_task = TaskMessage(
        task_id=rag_task.task_id, task_type="RAG_REINDEX",
        tenant_id=rag_task.tenant_id, security_level=rag_task.security_level,
        attempt_no=rag_task.attempt_no, pipeline_steps=rag_task.pipeline_steps,
        expected_input_version=rag_task.expected_input_version,
        meeting_id=rag_task.meeting_id, options=rag_task.options,
    )
    assert is_embedding_task(rag_task)

    audio_task = TaskMessage(
        task_id="t", task_type="MEETING_FULL_PIPELINE",
        tenant_id="x", security_level="INTERNAL", attempt_no=1,
        pipeline_steps=("AUDIO_PREPROCESS",),
        expected_input_version={"chunkStrategyVersion": "v1"},
    )
    assert not is_embedding_task(audio_task)


def test_to_callback_items_shapes_per_chunk_payload_for_embeddings_endpoint() -> None:
    from ai_worker.application.workflows.text_embedding import EmbeddingItem

    items = to_callback_items([
        EmbeddingItem(chunk_id="c1", content="alpha", values=(0.1, 0.2), dimension=2),
        EmbeddingItem(chunk_id="c2", content="beta", values=(0.3, 0.4), dimension=2),
    ])
    assert items == [
        {
            "chunkId": "c1",
            "sourceId": "c1",
            "sourceVersion": 1,
            "contentHash": "",
            "embedding": {"format": "FLOAT32_ARRAY", "dimension": 2, "values": [0.1, 0.2]},
        },
        {
            "chunkId": "c2",
            "sourceId": "c2",
            "sourceVersion": 1,
            "contentHash": "",
            "embedding": {"format": "FLOAT32_ARRAY", "dimension": 2, "values": [0.3, 0.4]},
        },
    ]


# ── Runtime integration ──────────────────────────────────────


def _callback_stub() -> AsyncMock:
    client = AsyncMock()
    client.update_step.return_value = CallbackResponse(http_status=200, accepted=True)
    client.submit_embeddings.return_value = CallbackResponse(http_status=200, accepted=True)
    client.complete_worker_phase.return_value = CallbackResponse(http_status=200, accepted=True)
    client.fail_task.return_value = CallbackResponse(http_status=200, accepted=True)
    return client


@pytest.mark.asyncio
async def test_runtime_routes_text_embedding_task_through_embed_workflow(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    callback_client = _callback_stub()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=None,  # audio engine kept default but never invoked
        embedding_workflow=workflow,
        state_store=state_store,
    )

    raw_message = {
        "taskId": "task_runtime_emb",
        "taskType": "TEXT_EMBEDDING",
        "tenantId": "tenant_01",
        "documentId": "doc_01",
        "securityLevel": "CONFIDENTIAL",
        "attemptNo": 1,
        "pipelineSteps": ["RAG_INDEXING"],
        "expectedInputVersion": {
            "chunkStrategyVersion": "default-zh-v1",
            "embeddingModelVersion": "bge-m3-v1",
        },
        "options": {
            "enableRagIndexing": True,
            "chunks": _chunks(("c1", "片段一"), ("c2", "片段二")),
        },
        "traceId": "trace_emb_01",
    }

    task = await runtime.consume_message(raw_message)

    assert task is not None
    callback_client.submit_embeddings.assert_awaited_once()
    submit_kwargs = callback_client.submit_embeddings.await_args.kwargs
    assert submit_kwargs["task_id"] == "task_runtime_emb"
    assert submit_kwargs["tenant_id"] == "tenant_01"
    assert submit_kwargs["source_type"] == "DOCUMENT"
    assert submit_kwargs["embedding_model_version"] == fake_runtime.model_version
    assert submit_kwargs["chunk_strategy_version"] == "default-zh-v1"
    assert submit_kwargs["embedding_batch_id"].startswith("embed_batch_task_runtime_emb_1_")
    assert len(submit_kwargs["items"]) == 2

    callback_client.complete_worker_phase.assert_awaited_once()
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["status"] == "SUCCEEDED"
    assert complete_kwargs["completed_steps"] == ["RAG_INDEXING"]


@pytest.mark.asyncio
async def test_runtime_fails_task_when_embeddings_callback_rejects(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    callback_client = _callback_stub()
    # Java side rejects (e.g., HMAC drift)
    callback_client.submit_embeddings.return_value = CallbackResponse(http_status=401, accepted=False)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        embedding_workflow=workflow,
        state_store=state_store,
    )

    raw_message = {
        "taskId": "task_runtime_emb_fail",
        "taskType": "TEXT_EMBEDDING",
        "tenantId": "tenant_01",
        "meetingId": "mtg_01",
        "securityLevel": "INTERNAL",
        "attemptNo": 1,
        "pipelineSteps": ["RAG_INDEXING"],
        "expectedInputVersion": {
            "chunkStrategyVersion": "default-zh-v1",
            "embeddingModelVersion": "bge-m3-v1",
        },
        "options": {"chunks": _chunks(("c1", "alpha"))},
        "traceId": "trace_fail",
    }

    await runtime.consume_message(raw_message)

    callback_client.submit_embeddings.assert_awaited_once()
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["failed_step"] == "RAG_INDEXING"
    assert fail_kwargs["error_code"] == "WRITEBACK_FAILED"
    callback_client.complete_worker_phase.assert_not_awaited()


@pytest.mark.asyncio
async def test_runtime_fails_task_when_chunks_missing(state_store, fake_runtime) -> None:
    workflow = TextEmbeddingWorkflow(state_store, fake_runtime)
    callback_client = _callback_stub()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        embedding_workflow=workflow,
        state_store=state_store,
    )

    raw_message = {
        "taskId": "task_runtime_empty",
        "taskType": "TEXT_EMBEDDING",
        "tenantId": "tenant_01",
        "documentId": "doc_01",
        "securityLevel": "INTERNAL",
        "attemptNo": 1,
        "pipelineSteps": ["RAG_INDEXING"],
        "expectedInputVersion": {
            "chunkStrategyVersion": "default-zh-v1",
            "embeddingModelVersion": "bge-m3-v1",
        },
        "options": {"chunks": []},
        "traceId": "trace_empty",
    }

    await runtime.consume_message(raw_message)

    callback_client.submit_embeddings.assert_not_awaited()
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["error_code"] == "TEXT_EMBEDDING_NO_CHUNKS"
