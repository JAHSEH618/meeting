"""TEXT_EMBEDDING / RAG_REINDEX workflow.

Handles the RAG_INDEXING step for `TEXT_EMBEDDING` and `RAG_REINDEX` task
messages produced by Java's {@code EmbeddingTaskDispatcher} (M5A C11).

The task message carries the chunk batch inline (id + content), so the
worker does not need to fetch chunks from Java — it just reads
{@code options.chunks}, runs each through the bge-m3 runtime, and returns
the embeddings as a list of {@link EmbeddingItem}s. The runtime layer
turns that into a {@code POST /internal/processing-tasks/{taskId}/embeddings}
callback (HMAC-protected) before completing the worker DAG.

A workflow is intentionally simple — no diarisation, no merge, no manifest.
Two responsibilities only:

1. Load bge-m3 (lazy, idempotent across calls thanks to the registry's
   asyncio.Lock).
2. Embed in a single batch (the task message is already capped at ≤32
   chunks per task by the dispatcher, well under the {@code /internal/embed}
   64-text cap).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

from ai_worker.application.workflows.audio_pipeline import WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact, TaskMessage
from ai_worker.model_runtime.embedding import BgeM3Runtime

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class EmbeddingItem:
    """Embedding result for a single chunk, ready for the writeback callback."""

    chunk_id: str
    content: str
    values: tuple[float, ...]
    dimension: int


@dataclass
class TextEmbeddingContext:
    task: TaskMessage
    embeddings: list[EmbeddingItem] = field(default_factory=list)
    model_version: str = ""
    skipped_steps: list[dict[str, str]] = field(default_factory=list)


class TextEmbeddingWorkflow:
    """Workflow engine for TEXT_EMBEDDING / RAG_REINDEX task types.

    The shape (start_pipeline / run_step / complete_pipeline) intentionally
    mirrors {@link LocalAudioPipelineEngine} so the runtime can treat both
    engines uniformly. Only RAG_INDEXING is implemented; everything else
    in {@code task.pipeline_steps} is recorded as a skipped step (defensive,
    although the dispatcher only emits RAG_INDEXING for these task types).
    """

    def __init__(
        self,
        state_store: InMemoryWorkflowStateStore,
        bge_m3_runtime: BgeM3Runtime,
    ) -> None:
        self._state_store = state_store
        self._bge_m3 = bge_m3_runtime

    async def run_pipeline(self, task: TaskMessage) -> tuple[PipelineArtifact, list[EmbeddingItem], str]:
        context = self.start_pipeline(task)
        for step_name in task.pipeline_steps:
            await self.run_step(context, step_name)
        artifact = await self.complete_pipeline(context)
        return artifact, context.embeddings, context.model_version

    def start_pipeline(self, task: TaskMessage) -> TextEmbeddingContext:
        self._state_store.start(
            task_id=task.task_id,
            task_type=task.task_type,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            trace_id=task.trace_id,
            steps=list(task.pipeline_steps),
        )
        return TextEmbeddingContext(task=task)

    async def run_step(self, context: TextEmbeddingContext, step_name: str) -> None:
        if step_name == "RAG_INDEXING":
            await self._run_rag_indexing(context)
            return
        # Defensive: the dispatcher only emits RAG_INDEXING for these tasks,
        # so an unknown step here means the contract drifted somewhere.
        context.skipped_steps.append({
            "stepName": step_name,
            "reason": "OUT_OF_TEXT_EMBEDDING_SCOPE",
        })

    async def complete_pipeline(self, context: TextEmbeddingContext) -> PipelineArtifact:
        return PipelineArtifact(
            task_id=context.task.task_id,
            transcript_segments=[],
            speaker_candidates=[],
            artifact_manifest_id=None,
            terminal_status="SUCCEEDED",
        )

    async def _run_rag_indexing(self, context: TextEmbeddingContext) -> None:
        task = context.task
        chunks = _extract_chunks(task)
        if not chunks:
            raise WorkerPipelineError(
                "RAG_INDEXING",
                "TEXT_EMBEDDING_NO_CHUNKS",
                "task.options.chunks is empty — nothing to embed",
                retryable=False,
            )

        try:
            await self._bge_m3.ensure_loaded()
        except Exception as exc:  # noqa: BLE001 — surface as workflow error
            raise WorkerPipelineError(
                "RAG_INDEXING",
                "EMBEDDING_MODEL_LOAD_FAILED",
                f"failed to load bge-m3: {exc}",
                retryable=True,
            ) from exc

        texts = [c.content for c in chunks]
        try:
            vectors = await self._bge_m3.aembed(texts)
        except Exception as exc:  # noqa: BLE001 — keep workflow self-contained
            raise WorkerPipelineError(
                "RAG_INDEXING",
                "EMBEDDING_FAILED",
                f"bge-m3 embed call failed: {exc}",
                retryable=True,
            ) from exc

        if len(vectors) != len(chunks):
            raise WorkerPipelineError(
                "RAG_INDEXING",
                "EMBEDDING_DIMENSION_MISMATCH",
                f"requested {len(chunks)} embeddings, got {len(vectors)}",
                retryable=False,
            )

        for chunk, vec in zip(chunks, vectors, strict=True):
            if not vec:
                raise WorkerPipelineError(
                    "RAG_INDEXING",
                    "EMBEDDING_EMPTY_VECTOR",
                    f"empty vector returned for chunk {chunk.chunk_id}",
                    retryable=True,
                )
            context.embeddings.append(EmbeddingItem(
                chunk_id=chunk.chunk_id,
                content=chunk.content,
                values=tuple(float(v) for v in vec),
                dimension=len(vec),
            ))

        context.model_version = self._bge_m3.model_version
        logger.info(
            "text_embedding_complete task_id=%s tenant_id=%s chunks=%d dim=%d model=%s",
            task.task_id,
            task.tenant_id,
            len(context.embeddings),
            context.embeddings[0].dimension if context.embeddings else 0,
            context.model_version,
        )


@dataclass(frozen=True)
class _TaskChunkRef:
    chunk_id: str
    content: str


def _extract_chunks(task: TaskMessage) -> list[_TaskChunkRef]:
    """Read the inline `chunks` array from the task message options.

    Falls back to an empty list if `options.chunks` is missing. The
    workflow then fails with TEXT_EMBEDDING_NO_CHUNKS — we never silently
    try to fetch chunks from Java because the dispatcher contract is to
    ship content inline.
    """
    raw = task.options.get("chunks") if isinstance(task.options, dict) else None
    if not isinstance(raw, list):
        return []
    out: list[_TaskChunkRef] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        chunk_id = entry.get("id")
        content = entry.get("content")
        if not isinstance(chunk_id, str) or not chunk_id:
            continue
        if not isinstance(content, str) or not content:
            continue
        out.append(_TaskChunkRef(chunk_id=chunk_id, content=content))
    return out


# Convenience for the runtime to discover whether a task should route to
# the embedding workflow instead of the audio pipeline.
EMBEDDING_TASK_TYPES = frozenset({"TEXT_EMBEDDING", "RAG_REINDEX"})


def is_embedding_task(task: TaskMessage) -> bool:
    return task.task_type in EMBEDDING_TASK_TYPES


def to_callback_items(embeddings: list[EmbeddingItem]) -> list[dict[str, Any]]:
    """Shape embeddings into the {@code EmbeddingsCallbackRequest.items} array.

    The Java callback validates per-item {@code embedding.dimension == 1024}
    against the bge-m3 contract, so we emit the dimension we measured rather
    than hard-coding it here.
    """
    items: list[dict[str, Any]] = []
    for emb in embeddings:
        items.append({
            "chunkId": emb.chunk_id,
            "sourceId": emb.chunk_id,  # ID-only batch, no upstream source linkage
            "sourceVersion": 1,
            "contentHash": "",  # Java already stored content_hash at chunk-write time
            "embedding": {
                "format": "FLOAT32_ARRAY",
                "dimension": emb.dimension,
                "values": list(emb.values),
            },
        })
    return items
