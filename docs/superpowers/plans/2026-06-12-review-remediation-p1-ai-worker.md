# AI Worker Remediation Plan (Review P1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the production ai-worker consumer able to run a contract-valid 8-step MEETING_FULL_PIPELINE message to completion (degrading ALIGNMENT/RAG_INDEXING per SPEC §6.3/§10), keep long GPU steps alive via periodic heartbeats, survive broker heartbeat starvation and unexpected exceptions without losing messages, and close the security/contract gaps (HMAC path+query, fail-closed secrets, OOM exit, error-code registry drift, artifacts callback, rerank ordering, admin BFF upstream errors).

**Architecture:** All fixes stay inside the existing pika BlockingConnection consumer + MvpWorkerRuntime + LocalAudioPipelineEngine stack (no Dramatiq/Prefect migration — locked decision D2). Pipeline execution moves to a worker thread with thread-safe ack/reject; heartbeats become a per-step asyncio task; step degradation is an engine capability (`step_skip_reason`) consulted by the runtime; every worker-emitted error code is registered in `packages/meeting-contracts/schemas/common/error-codes.yaml`.

**Tech Stack:** Python 3.11, FastAPI, pika 1.x BlockingConnection, httpx, pydantic-settings, pytest/pytest-asyncio, uv; OpenAPI/JSON Schema contracts in `packages/meeting-contracts`.
**Branch:** `fix/review-remediation-p1-ai-worker`
**Source review:** 2026-06-12 four-workspace code review — this volume fixes ai-worker Critical #1–#4 and Important #5–#12.

---

## Verification notes (review claims checked against actual source)

All file:line refs below were re-verified on 2026-06-12. Adjustments found during inspection:

1. **I5 (rerank)** — review implied no sorting exists. Actual code at `main.py:934-943` already sorts by score desc with index tie-break; only the pre-truncation at `main.py:918` is wrong. The fix is to score *all* candidates and slice `[:topN]` after the existing sort.
2. **D3 (RAG_INDEXING) investigation result** — worker-side real implementation is **not possible** with message-only data: Java creates `knowledge_chunks` rows first and ships chunk id+content inline via separate `TEXT_EMBEDDING`/`RAG_REINDEX` tasks (`EmbeddingTaskDispatcher`, `options.chunks`); the embeddings callback updates *existing* chunk rows by `chunkId` (`EmbeddingsCallbackApplicationService`). A MEETING_FULL_PIPELINE message carries no chunk ids/content, and the worker cannot mint chunkIds Java recognizes. **Decision: degrade RAG_INDEXING (and ALIGNMENT) into `skippedSteps` + `status=PARTIAL_SUCCEEDED`**, which is exactly what `apps/ai-worker/SPEC.md` line 334 sanctions ("`ALIGNMENT`、`RAG_INDEXING`、`SPEAKER_MATCHING` 可降级时通过 `/complete phase=WORKER_DAG status=PARTIAL_SUCCEEDED + skippedSteps`"). Follow-up note for the Java-side P2 plan recorded at the bottom.
3. **D3 (ALIGNMENT)** — Java's `phase2TaskMessagePayload` sends `options.enableAlignment=true` (ProcessingTaskApplicationService.java:437), so the "enabled but unimplemented → skip + WARN log" branch is the *live* path, not a corner case.
4. **I7** — `ensure_admin_config()` in `admin/router.py:20-32` confirmed dead (zero call sites; `AI_WORKER_ENABLE_ADMIN` exists only in its docstring). It is removed in Task 11.
5. **I9** — `PipelineArtifact.artifact_manifest_id` currently holds the manifest **URI** (`tos://…`), while the manifest JSON body carries the logical id `artifact_manifest_{taskId}_{attemptNo}`. Task 7 splits these into `artifact_manifest_id` (logical) + `artifact_manifest_uri` (location). Also: the worker's internal artifact dicts use key `category`, but `ArtifactCallbackRequest` requires `artifactType` — renamed in Task 7.
6. **D5** — `packages/meeting-contracts/scripts/check-consistency.sh` only *counts* error-codes.yaml entries (no cross-language error-code enum gate), so adding codes is safe contracts-side. Full grep-verified worker-emitted code list is in Task 1.
7. **C4** — the missing-audio-stream `next(...)` at `preprocess.py:104-107` raises `StopIteration`, which escapes the `except (KeyError, TypeError, ValueError, json.JSONDecodeError)` at line 90 and is converted to `RuntimeError` by the coroutine machinery. Claim confirmed.
8. **Heartbeat count assertion** — `tests/test_worker_runtime.py:194` pins `update_step.await_count == steps * 3` (RUNNING(0) + fake heartbeat(50) + SUCCEEDED(100)). After D1 the fixed pre-work heartbeat disappears, so this becomes `steps * 2`; updated in Task 4.
9. **`tests/test_qwen3_asr_runtime.py:49`** pins the wrong `ASR_MODEL_TIMEOUT` mapping for load failures; updated in Task 2.
10. **D1 protocol** — worker already sends `X-Lease-Owner: {workerId}:{taskId}:{attemptNo}` and stable heartbeat idempotency keys (`client.py:64`, `client.py:359-376`); no header/protocol change needed worker-side. Java side (parallel P2 plan) stops pre-claiming the lease and renews +120s on heartbeat.

---

## File Structure

### Contracts
- Modify: `packages/meeting-contracts/schemas/common/error-codes.yaml` (register 19 worker-emitted codes — Task 1)

### Worker source (`apps/ai-worker/`)
- Modify: `ai_worker/pipeline/audio/preprocess.py` (canonical `AUDIO_UNSUPPORTED_FORMAT`; missing-audio-stream → `AUDIO_CORRUPTED` — Tasks 2, 5)
- Modify: `ai_worker/model_runtime/asr/qwen3_asr_runtime.py` (load failure → `ASR_MODEL_LOAD_FAILED`; CUDA OOM → `ASR_GPU_OOM` — Tasks 2, 8)
- Modify: `ai_worker/model_runtime/diarization/pyannote_runtime.py` (CUDA OOM → `DIARIZATION_GPU_OOM` — Task 8)
- Modify: `ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py` (CUDA OOM → `SPEAKER_EMBEDDING_GPU_OOM` — Task 8)
- Modify: `ai_worker/application/workflows/audio_pipeline.py` (`step_skip_reason`, degraded PARTIAL_SUCCEEDED, `artifactType` key, manifest id/uri split — Tasks 3, 7)
- Modify: `ai_worker/domain/task/models.py` (`PipelineArtifact.artifact_manifest_uri` — Task 7)
- Modify: `ai_worker/infrastructure/worker_runtime.py` (skip consult, heartbeat loop, top-level exception guard, artifacts callback, OOM exit flag — Tasks 3, 4, 5, 7, 8)
- Modify: `ai_worker/infrastructure/mq/rabbitmq_consumer.py` (worker thread + `add_callback_threadsafe` ack/reject, OOM exit dispatch — Tasks 6, 8)
- Modify: `ai_worker/interfaces/api/main.py` (rerank full-candidate scoring, HMAC path+query, `/internal/ready` secret check, startup guard — Tasks 9, 10, 11)
- Modify: `ai_worker/interfaces/workers/rabbitmq.py` (startup secret guard — Task 11)
- Modify: `ai_worker/common/config.py` (`env` field / `AI_WORKER_ENV` — Task 11)
- Create: `ai_worker/common/secret_guard.py` (Task 11)
- Modify: `ai_worker/observability/gpu_metrics.py` (`is_cuda_oom` helper — Task 8)
- Modify: `ai_worker/admin/java_client.py` (`UpstreamUnavailableError` — Task 12)
- Modify: `ai_worker/admin/envelopes.py` (`parse_json_body`, `MalformedJsonBodyError` — Task 12)
- Modify: `ai_worker/admin/router.py` (remove dead `ensure_admin_config`; add `register_admin_exception_handlers` — Tasks 11, 12)
- Modify: `ai_worker/admin/meetings.py`, `ai_worker/admin/persons.py`, `ai_worker/admin/files.py`, `ai_worker/admin/enrollment.py` (guarded JSON body parsing — Task 12)
- Modify: `ai_worker/infrastructure/speaker/reference_client.py` (`time.sleep` → `await asyncio.sleep` — Task 12)
- Modify: `apps/ai-worker/SPEC.md` (record Dramatiq/Prefect deferral — Task 6)

### Tests (`apps/ai-worker/tests/`)
- Create: `tests/test_error_code_contract.py` (Task 2)
- Create: `tests/test_preprocess.py` (Tasks 2, 5)
- Modify: `tests/test_qwen3_asr_runtime.py` (Task 2)
- Modify: `tests/test_audio_pipeline.py` (Tasks 3, 7)
- Modify: `tests/test_worker_runtime.py` (Tasks 3, 4, 5, 7, 8)
- Modify: `tests/test_rabbitmq_consumer.py` (Tasks 6, 8)
- Modify: `tests/test_rerank.py` (Task 9)
- Create: `tests/test_hmac_path_query.py` (Task 10)
- Create: `tests/test_secret_guard.py` (Task 11)
- Create: `tests/test_gpu_oom.py` (Task 8)
- Create: `tests/admin/test_upstream_errors.py` (Task 12)
- Create: `tests/test_speaker_reference_client_retry.py` (Task 12)
- Create: `tests/test_meeting_full_pipeline_e2e.py` (Task 13)

All `uv run …` commands below run from `apps/ai-worker/`. Contracts commands run from `packages/meeting-contracts/`.

---

## Task 0: Branch setup

- [x] **Step 1: Create the remediation branch**

```bash
cd /Users/friedhelmliu/CodeSpace/meeting
git checkout master && git pull
git checkout -b fix/review-remediation-p1-ai-worker
```

---

## Task 1 (D5, I10-contracts): Register all worker-emitted error codes in the contract registry

**Files:**
- Modify: `packages/meeting-contracts/schemas/common/error-codes.yaml`

Grep-verified worker-emitted codes missing from the registry (sources: `audio_pipeline.py`, `preprocess.py`, `text_embedding.py`, `worker_runtime.py`, `qwen3_asr_runtime.py`, `pyannote_runtime.py`, `cam_plus_plus_runtime.py`, `task_consumer.py`): `WORKER_INTERNAL_ERROR` (new, D4), `WORKER_STEP_NOT_IMPLEMENTED`, `PIPELINE_STEP_FAILED`, `AUDIO_OBJECT_NOT_FOUND`, `AUDIO_SOURCE_MISSING`, `AUDIO_PREPROCESS_MISSING`, `AUDIO_PREPROCESS_RUNTIME_MISSING`, `AUDIO_SAMPLE_RATE_TOO_LOW`, `ASR_EMPTY_RESULT`, `ASR_MODEL_LOAD_FAILED` (new, I10), `DIARIZATION_EMPTY_TURNS`, `DIARIZATION_GPU_OOM` (new, D10), `SPEAKER_EMBEDDING_GPU_OOM` (new, D10), `TRANSCRIPT_MERGE_EMPTY`, `TEXT_EMBEDDING_NO_CHUNKS`, `EMBEDDING_MODEL_LOAD_FAILED`, `EMBEDDING_FAILED`, `EMBEDDING_DIMENSION_MISMATCH`, `EMBEDDING_EMPTY_VECTOR`. Already registered (no action): `INVALID_TASK_MESSAGE`, `WRITEBACK_FAILED`, `AUDIO_CORRUPTED`, `AUDIO_TOO_LONG`, `AUDIO_UNSUPPORTED_FORMAT`, `ASR_RUNTIME_ERROR`, `ASR_MODEL_TIMEOUT`, `ASR_GPU_OOM`, `DIARIZATION_FAILED`, `SPEAKER_EMBEDDING_FAILED`, `SPEAKER_MATCH_FAILED`, `SPEAKER_REFERENCE_UNAVAILABLE`, `TRANSCRIPT_MERGE_FAILED`.

- [x] **Step 1: Append the audio-pipeline codes** — in `error-codes.yaml`, inside the `# ── Audio Pipeline ──` section, after the `TRANSCRIPT_MERGE_FAILED` entry, add:

```yaml
  - code: AUDIO_OBJECT_NOT_FOUND
    step: AUDIO_PREPROCESS
    retryable: true
    userMessage: 音频对象不存在或暂不可读
    i18nKey: errors.AUDIO_OBJECT_NOT_FOUND
    opsTags: [audio, storage]
  - code: AUDIO_SOURCE_MISSING
    step: AUDIO_PREPROCESS
    retryable: false
    userMessage: 任务缺少音频地址
    i18nKey: errors.AUDIO_SOURCE_MISSING
    opsTags: [audio, task, validation]
  - code: AUDIO_PREPROCESS_MISSING
    step: AUDIO_PREPROCESS
    retryable: false
    userMessage: 音频预处理结果缺失
    i18nKey: errors.AUDIO_PREPROCESS_MISSING
    opsTags: [audio, pipeline]
  - code: AUDIO_PREPROCESS_RUNTIME_MISSING
    step: AUDIO_PREPROCESS
    retryable: false
    userMessage: 音频预处理依赖缺失（ffprobe）
    i18nKey: errors.AUDIO_PREPROCESS_RUNTIME_MISSING
    opsTags: [audio, ffmpeg, env]
  - code: AUDIO_SAMPLE_RATE_TOO_LOW
    step: AUDIO_PREPROCESS
    retryable: false
    userMessage: 音频采样率低于 16kHz
    i18nKey: errors.AUDIO_SAMPLE_RATE_TOO_LOW
    opsTags: [audio, validation]
  - code: ASR_EMPTY_RESULT
    step: ASR
    retryable: true
    userMessage: ASR 未产出任何转录内容
    i18nKey: errors.ASR_EMPTY_RESULT
    opsTags: [asr]
  - code: ASR_MODEL_LOAD_FAILED
    step: ASR
    retryable: true
    userMessage: ASR 模型加载失败
    i18nKey: errors.ASR_MODEL_LOAD_FAILED
    opsTags: [asr, model]
  - code: DIARIZATION_EMPTY_TURNS
    step: DIARIZATION
    retryable: true
    userMessage: 说话人分离未产出任何片段
    i18nKey: errors.DIARIZATION_EMPTY_TURNS
    opsTags: [diarization]
  - code: DIARIZATION_GPU_OOM
    step: DIARIZATION
    retryable: true
    userMessage: 说话人分离 GPU 显存不足
    i18nKey: errors.DIARIZATION_GPU_OOM
    opsTags: [diarization, gpu, oom]
  - code: SPEAKER_EMBEDDING_GPU_OOM
    step: SPEAKER_EMBEDDING
    retryable: true
    userMessage: 声纹特征提取 GPU 显存不足
    i18nKey: errors.SPEAKER_EMBEDDING_GPU_OOM
    opsTags: [speaker, gpu, oom]
  - code: TRANSCRIPT_MERGE_EMPTY
    step: TRANSCRIPT_MERGE
    retryable: true
    userMessage: 转录合并结果为空
    i18nKey: errors.TRANSCRIPT_MERGE_EMPTY
    opsTags: [transcript]
```

- [x] **Step 2: Append the RAG-indexing codes** — in the `# ── RAG ──` section, after `RAG_RATE_LIMITED`, add:

```yaml
  - code: TEXT_EMBEDDING_NO_CHUNKS
    step: RAG_INDEXING
    retryable: false
    userMessage: 嵌入任务缺少待处理分块
    i18nKey: errors.TEXT_EMBEDDING_NO_CHUNKS
    opsTags: [rag, embedding, task]
  - code: EMBEDDING_MODEL_LOAD_FAILED
    step: RAG_INDEXING
    retryable: true
    userMessage: 向量模型加载失败
    i18nKey: errors.EMBEDDING_MODEL_LOAD_FAILED
    opsTags: [rag, embedding, model]
  - code: EMBEDDING_FAILED
    step: RAG_INDEXING
    retryable: true
    userMessage: 向量化推理失败
    i18nKey: errors.EMBEDDING_FAILED
    opsTags: [rag, embedding]
  - code: EMBEDDING_DIMENSION_MISMATCH
    step: RAG_INDEXING
    retryable: false
    userMessage: 向量维度不符合预期
    i18nKey: errors.EMBEDDING_DIMENSION_MISMATCH
    opsTags: [rag, embedding, contract]
  - code: EMBEDDING_EMPTY_VECTOR
    step: RAG_INDEXING
    retryable: true
    userMessage: 向量化返回空向量
    i18nKey: errors.EMBEDDING_EMPTY_VECTOR
    opsTags: [rag, embedding]
```

- [x] **Step 3: Add a new worker-runtime section** — before the `# ── Infra ──` section, add:

```yaml
  # ── Worker runtime (ai-worker emitted) ────────────────────────
  - code: WORKER_INTERNAL_ERROR
    step: TASK
    retryable: true
    userMessage: Worker 内部错误
    i18nKey: errors.WORKER_INTERNAL_ERROR
    opsTags: [worker, internal]
  - code: WORKER_STEP_NOT_IMPLEMENTED
    step: TASK
    retryable: false
    userMessage: Worker 不支持该处理步骤
    i18nKey: errors.WORKER_STEP_NOT_IMPLEMENTED
    opsTags: [worker, pipeline]
  - code: PIPELINE_STEP_FAILED
    step: TASK
    retryable: true
    userMessage: 处理步骤失败
    i18nKey: errors.PIPELINE_STEP_FAILED
    opsTags: [worker, pipeline]
```

- [x] **Step 4: Run the contracts CI gate**

```bash
cd packages/meeting-contracts
npm run check
```

Expected: all checks pass; the error-codes count line reflects 19 new entries.

- [x] **Step 5: Regenerate codegen targets and verify zero drift**

```bash
npm run codegen
git status --short
```

Expected: error codes are not a codegen input, so `git status` shows only `schemas/common/error-codes.yaml` modified (no generated-file drift). If any generated file changes, commit it together.

- [x] **Step 6: Commit**

```bash
git add packages/meeting-contracts/schemas/common/error-codes.yaml
git commit -m "fix(contracts): register ai-worker emitted error codes (review P1 D5/I10)"
```

---

## Task 2 (I10, D5): Fix error-code drift in worker source + add registry contract test

**Files:**
- Modify: `ai_worker/pipeline/audio/preprocess.py` (line 100)
- Modify: `ai_worker/model_runtime/asr/qwen3_asr_runtime.py` (lines 138-141)
- Modify: `tests/test_qwen3_asr_runtime.py` (lines 49, 52)
- Create: `tests/test_preprocess.py`
- Create: `tests/test_error_code_contract.py`

- [x] **Step 1: Write the failing tests.** Create `tests/test_preprocess.py`:

```python
from __future__ import annotations

import pytest

from ai_worker.pipeline.audio.preprocess import (
    AudioMetadata,
    AudioPreprocessError,
    FfprobeAudioPreprocessor,
)


def _metadata(codec: str = "pcm_s16le") -> AudioMetadata:
    return AudioMetadata(
        duration_ms=1_000,
        sample_rate_hz=16_000,
        channels=1,
        codec=codec,
        bitrate=256_000,
        format_name="wav",
    )


def test_validate_maps_unknown_codec_to_canonical_code() -> None:
    with pytest.raises(AudioPreprocessError) as exc_info:
        FfprobeAudioPreprocessor._validate(_metadata(codec="unknown"))
    # Canonical registry code is AUDIO_UNSUPPORTED_FORMAT (error-codes.yaml:151),
    # not the drifting AUDIO_FORMAT_UNSUPPORTED.
    assert exc_info.value.error_code == "AUDIO_UNSUPPORTED_FORMAT"
```

Create `tests/test_error_code_contract.py`:

```python
"""D5 — every code the worker can emit toward Java must exist in
packages/meeting-contracts/schemas/common/error-codes.yaml.

Mirrors tests/test_workflow_registry_contract.py: read the contract file
from the repo root and compare against an explicit, hand-maintained set.
When you add a new raise site, add the code here AND to the YAML registry.
"""

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
ERROR_CODES_YAML = (
    REPO_ROOT / "packages" / "meeting-contracts" / "schemas" / "common" / "error-codes.yaml"
)

WORKER_EMITTED_ERROR_CODES = frozenset({
    # consumer / runtime level
    "INVALID_TASK_MESSAGE",
    "WORKER_INTERNAL_ERROR",
    "WORKER_STEP_NOT_IMPLEMENTED",
    "PIPELINE_STEP_FAILED",
    "WRITEBACK_FAILED",
    # audio preprocess
    "AUDIO_OBJECT_NOT_FOUND",
    "AUDIO_SOURCE_MISSING",
    "AUDIO_PREPROCESS_MISSING",
    "AUDIO_PREPROCESS_RUNTIME_MISSING",
    "AUDIO_CORRUPTED",
    "AUDIO_TOO_LONG",
    "AUDIO_SAMPLE_RATE_TOO_LOW",
    "AUDIO_UNSUPPORTED_FORMAT",
    # asr
    "ASR_EMPTY_RESULT",
    "ASR_RUNTIME_ERROR",
    "ASR_MODEL_TIMEOUT",
    "ASR_MODEL_LOAD_FAILED",
    "ASR_GPU_OOM",
    # diarization
    "DIARIZATION_FAILED",
    "DIARIZATION_EMPTY_TURNS",
    "DIARIZATION_GPU_OOM",
    # speaker
    "SPEAKER_EMBEDDING_FAILED",
    "SPEAKER_EMBEDDING_GPU_OOM",
    "SPEAKER_MATCH_FAILED",
    "SPEAKER_REFERENCE_UNAVAILABLE",
    # transcript merge
    "TRANSCRIPT_MERGE_EMPTY",
    "TRANSCRIPT_MERGE_FAILED",
    # text embedding workflow
    "TEXT_EMBEDDING_NO_CHUNKS",
    "EMBEDDING_MODEL_LOAD_FAILED",
    "EMBEDDING_FAILED",
    "EMBEDDING_DIMENSION_MISMATCH",
    "EMBEDDING_EMPTY_VECTOR",
})

WORKER_SRC = Path(__file__).resolve().parents[1] / "ai_worker"


def _registry_codes() -> set[str]:
    text = ERROR_CODES_YAML.read_text(encoding="utf-8")
    return set(re.findall(r"^\s*-\s*code:\s*([A-Z0-9_]+)\s*$", text, flags=re.M))


def test_worker_emitted_error_codes_are_registered() -> None:
    missing = WORKER_EMITTED_ERROR_CODES - _registry_codes()
    assert not missing, f"unregistered worker error codes: {sorted(missing)}"


def test_drifted_audio_format_code_is_gone_from_source() -> None:
    preprocess_src = (WORKER_SRC / "pipeline" / "audio" / "preprocess.py").read_text()
    assert "AUDIO_FORMAT_UNSUPPORTED" not in preprocess_src
```

Add to `tests/test_qwen3_asr_runtime.py` (new test; the existing one is updated in Step 4):

```python
@pytest.mark.asyncio
async def test_real_mode_load_failure_maps_to_asr_model_load_failed(tmp_path):
    runtime = Qwen3AsrRuntime(
        use_fake=False,
        models_dir=tmp_path / "does-not-exist",
        device="cpu",
    )
    with pytest.raises(Qwen3AsrRuntimeError) as ex:
        await runtime.ensure_loaded()
    assert ex.value.error_code == "ASR_MODEL_LOAD_FAILED"
```

- [x] **Step 2: Run the new tests, expect failures**

```bash
uv run pytest tests/test_preprocess.py::test_validate_maps_unknown_codec_to_canonical_code tests/test_error_code_contract.py tests/test_qwen3_asr_runtime.py::test_real_mode_load_failure_maps_to_asr_model_load_failed -v
```

Expected: `test_validate_maps_unknown_codec_to_canonical_code` fails (`AUDIO_FORMAT_UNSUPPORTED != AUDIO_UNSUPPORTED_FORMAT`), `test_drifted_audio_format_code_is_gone_from_source` fails, ASR load test fails (`ASR_MODEL_TIMEOUT != ASR_MODEL_LOAD_FAILED`). `test_worker_emitted_error_codes_are_registered` passes (Task 1 added the codes).

- [x] **Step 3: Fix `preprocess.py:100`** — in `FfprobeAudioPreprocessor._validate`:

```python
        if metadata.codec.lower() in {"unknown", ""}:
            raise AudioPreprocessError("AUDIO_UNSUPPORTED_FORMAT", "audio codec is unsupported")
```

- [x] **Step 4: Fix the ASR load-failure code.** In `qwen3_asr_runtime.py:138-141`, replace:

```python
                    raise Qwen3AsrRuntimeError(
                        "ASR_MODEL_TIMEOUT",
                        f"failed to load qwen3-asr: {exc}",
                    ) from exc
```

with:

```python
                    raise Qwen3AsrRuntimeError(
                        "ASR_MODEL_LOAD_FAILED",
                        f"failed to load qwen3-asr: {exc}",
                    ) from exc
```

Also update the class docstring line 45 (`callback layer maps it to ASR_MODEL_TIMEOUT / ASR_RUNTIME_ERROR`) to mention `ASR_MODEL_LOAD_FAILED / ASR_RUNTIME_ERROR`. Then update the existing pinned test `tests/test_qwen3_asr_runtime.py::test_real_mode_raises_when_weights_missing` lines 49 and 52:

```python
    assert ex.value.error_code == "ASR_MODEL_LOAD_FAILED"
    assert runtime.status == "ERROR"
    assert runtime.last_error is not None
    assert "weights not found" in runtime.last_error or "FileNotFoundError" in runtime.last_error
```

- [x] **Step 5: Run again, expect pass**

```bash
uv run pytest tests/test_preprocess.py tests/test_error_code_contract.py tests/test_qwen3_asr_runtime.py -v
```

- [x] **Step 6: Commit**

```bash
git add apps/ai-worker/ai_worker/pipeline/audio/preprocess.py apps/ai-worker/ai_worker/model_runtime/asr/qwen3_asr_runtime.py apps/ai-worker/tests/test_preprocess.py apps/ai-worker/tests/test_error_code_contract.py apps/ai-worker/tests/test_qwen3_asr_runtime.py
git commit -m "fix(worker): align emitted error codes with contract registry (I10/D5)"
```

---

## Task 3 (C1, D3): Degrade ALIGNMENT and RAG_INDEXING instead of failing every full-pipeline task

Today every contract-valid MEETING_FULL_PIPELINE message deterministically fails at step 3 (`ALIGNMENT`) with non-retryable `WORKER_STEP_NOT_IMPLEMENTED` (`audio_pipeline.py:95-101`), because the validator requires the exact 8-step list Java sends (`task_validator.py:72-75`, `ProcessingTaskApplicationService.MEETING_WORKER_STEPS`).

Design: `LocalAudioPipelineEngine` gains `step_skip_reason(task, step_name) -> str | None`. `MvpWorkerRuntime` consults it **before** `execute_step`, so skipped steps get **no** step callbacks (mirroring the existing enrollment skip), are recorded as `SKIPPED` in the state store and in `context.skipped_steps`, and are excluded from `completedSteps`. `complete_pipeline` returns `terminal_status="PARTIAL_SUCCEEDED"` when an ALIGNMENT/RAG_INDEXING skip was recorded (SPEC §10 line 334); the enrollment `NOT_REQUIRED_FOR_ENROLLMENT` skip keeps `SUCCEEDED`. `run_step` keeps the fail-closed `WORKER_STEP_NOT_IMPLEMENTED` branch for genuinely unknown steps and also honors the skip internally so `engine.run_pipeline` stays self-consistent.

**Files:**
- Modify: `ai_worker/application/workflows/audio_pipeline.py`
- Modify: `ai_worker/infrastructure/worker_runtime.py`
- Modify: `tests/test_audio_pipeline.py`
- Modify: `tests/test_worker_runtime.py`

- [x] **Step 1: Write failing engine tests.** In `tests/test_audio_pipeline.py`, REPLACE `test_audio_pipeline_fails_required_steps_that_are_not_implemented` (lines 266-277) with:

```python
@pytest.mark.asyncio
async def test_run_step_fails_closed_for_unknown_required_step() -> None:
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore())
    context = engine.start_pipeline(
        _task_with_steps("tos://meeting-audio-auska/raw.wav", ("DIARIZATION",))
    )

    with pytest.raises(WorkerPipelineError) as exc_info:
        await engine.run_step(context, "SOME_FUTURE_STEP")

    assert exc_info.value.error_code == "WORKER_STEP_NOT_IMPLEMENTED"
    assert not exc_info.value.retryable


def test_step_skip_reason_for_degradable_steps() -> None:
    engine = LocalAudioPipelineEngine(InMemoryWorkflowStateStore())
    task_default = _task("tos://meeting-audio-auska/raw.wav")  # options have no enableAlignment
    assert engine.step_skip_reason(task_default, "ALIGNMENT") == "ALIGNMENT_DISABLED_DEFAULT_OFF"
    assert engine.step_skip_reason(task_default, "RAG_INDEXING") == "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"
    assert engine.step_skip_reason(task_default, "ASR") is None

    task_enabled = _task_with_steps("tos://meeting-audio-auska/raw.wav", ("ALIGNMENT",))
    task_enabled.options["enableAlignment"] = True
    # Java's phase2 payload sends enableAlignment=true today — must still skip, never fail.
    assert engine.step_skip_reason(task_enabled, "ALIGNMENT") == "ALIGNMENT_NOT_IMPLEMENTED"


@pytest.mark.asyncio
async def test_full_pipeline_degrades_alignment_and_rag_indexing(tmp_path: Path) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe is required for audio preprocess smoke")
    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "raw.wav"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path)
    audio_path.with_suffix(audio_path.suffix + ".txt").write_text("降级测试", encoding="utf-8")

    engine = LocalAudioPipelineEngine(
        InMemoryWorkflowStateStore(),
        artifact_store=LocalArtifactStore(audio_root),
    )
    task = _task_with_steps(
        "tos://meeting-audio-auska/raw.wav",
        (
            "AUDIO_PREPROCESS", "ASR", "ALIGNMENT", "DIARIZATION",
            "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE", "RAG_INDEXING",
        ),
    )

    artifact = await engine.run_pipeline(task)

    assert artifact.terminal_status == "PARTIAL_SUCCEEDED"
    assert artifact.transcript_segments[0]["text"] == "降级测试"
```

- [x] **Step 2: Run, expect failures**

```bash
uv run pytest tests/test_audio_pipeline.py -v
```

Expected: `test_step_skip_reason_for_degradable_steps` fails with `AttributeError: ... no attribute 'step_skip_reason'`; `test_full_pipeline_degrades_alignment_and_rag_indexing` fails with `WorkerPipelineError: WORKER_STEP_NOT_IMPLEMENTED`.

- [x] **Step 3: Implement the engine changes.** In `ai_worker/application/workflows/audio_pipeline.py`:

Add near the top (after the imports):

```python
import logging

logger = logging.getLogger(__name__)

# SPEC §6.3 / §10: ALIGNMENT and RAG_INDEXING are degradable inside
# MEETING_FULL_PIPELINE — they are reported via /complete skippedSteps +
# status=PARTIAL_SUCCEEDED instead of failing the task. RAG_INDEXING for
# meetings is performed Java-side (chunking + TEXT_EMBEDDING dispatch);
# the worker cannot mint chunkIds Java recognizes (D3 investigation).
DEGRADED_SKIP_STEPS = frozenset({"ALIGNMENT", "RAG_INDEXING"})
```

Add the capability method to `LocalAudioPipelineEngine` (after `start_pipeline`):

```python
    def step_skip_reason(self, task: TaskMessage, step_name: str) -> str | None:
        """Return a skippedSteps reason when this engine degrades the step.

        None means the step must execute (or fail closed in run_step).
        """
        if step_name == "ALIGNMENT":
            options = task.options if isinstance(task.options, dict) else {}
            if options.get("enableAlignment"):
                logger.warning(
                    "ALIGNMENT requested via options.enableAlignment but not implemented; "
                    "skipping (degraded): task_id=%s",
                    task.task_id,
                )
                return "ALIGNMENT_NOT_IMPLEMENTED"
            return "ALIGNMENT_DISABLED_DEFAULT_OFF"
        if step_name == "RAG_INDEXING":
            logger.info(
                "RAG_INDEXING in MEETING_FULL_PIPELINE is deferred to Java-side chunking "
                "+ TEXT_EMBEDDING dispatch; skipping (degraded): task_id=%s",
                task.task_id,
            )
            return "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"
        return None
```

Rewrite `run_step` so the engine-internal path (`run_pipeline`) also skips:

```python
    async def run_step(self, context: "_PipelineContext", step_name: str) -> None:
        skip_reason = self.step_skip_reason(context.task, step_name)
        if skip_reason is not None:
            _record_skip(context, step_name, skip_reason)
            return
        if step_name == "AUDIO_PREPROCESS":
            await self._run_audio_preprocess(context)
        elif step_name == "ASR":
            await self._run_asr(context)
        elif step_name == "DIARIZATION":
            await self._run_diarization(context)
        elif step_name == "SPEAKER_EMBEDDING":
            await self._run_speaker_embedding(context)
        elif step_name == "SPEAKER_MATCHING":
            await self._run_speaker_matching(context)
        elif step_name == "TRANSCRIPT_MERGE":
            await self._run_transcript_merge(context)
        else:
            raise WorkerPipelineError(
                step_name,
                "WORKER_STEP_NOT_IMPLEMENTED",
                f"worker step is required but not implemented by LocalAudioPipelineEngine: {step_name}",
                retryable=False,
            )
```

Update `complete_pipeline` to compute the degraded status (manifest id/uri split happens later, in Task 7 — do not change those fields here):

```python
    async def complete_pipeline(self, context: "_PipelineContext") -> PipelineArtifact:
        manifest_ref = await self._write_manifest(context)
        degraded = any(
            s.get("stepName") in DEGRADED_SKIP_STEPS for s in context.skipped_steps
        )
        return PipelineArtifact(
            task_id=context.task.task_id,
            transcript_segments=context.transcript_segments,
            speaker_candidates=context.speaker_candidates,
            artifact_manifest_id=manifest_ref.uri,
            terminal_status="PARTIAL_SUCCEEDED" if degraded else "SUCCEEDED",
        )
```

Add the module-level helper (next to `_artifact_dict`):

```python
def _record_skip(context: "_PipelineContext", step_name: str, reason: str) -> None:
    if any(s.get("stepName") == step_name for s in context.skipped_steps):
        return
    context.skipped_steps.append({"stepName": step_name, "reason": reason})
```

- [x] **Step 4: Run the engine tests, expect pass**

```bash
uv run pytest tests/test_audio_pipeline.py -v
```

- [x] **Step 5: Write the failing runtime test.** In `tests/test_worker_runtime.py`, add:

```python
class SkippingWorkflowEngine(StubWorkflowEngine):
    """Engine that declares ALIGNMENT / RAG_INDEXING as degradable skips,
    mirroring LocalAudioPipelineEngine.step_skip_reason."""

    def step_skip_reason(self, task, step_name: str) -> str | None:
        if step_name == "ALIGNMENT":
            return "ALIGNMENT_DISABLED_DEFAULT_OFF"
        if step_name == "RAG_INDEXING":
            return "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"
        return None


@pytest.mark.asyncio
async def test_degradable_steps_are_skipped_without_step_callbacks(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SkippingWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_not_awaited()
    assert engine.ran_steps == [
        "AUDIO_PREPROCESS", "ASR", "DIARIZATION",
        "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE",
    ]
    step_callback_names = {c.kwargs["step_name"] for c in callback_client.update_step.await_args_list}
    assert "ALIGNMENT" not in step_callback_names
    assert "RAG_INDEXING" not in step_callback_names
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["completed_steps"] == [
        "AUDIO_PREPROCESS", "ASR", "DIARIZATION",
        "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE",
    ]
    assert complete_kwargs["skipped_steps"] == [
        {"stepName": "ALIGNMENT", "reason": "ALIGNMENT_DISABLED_DEFAULT_OFF"},
        {"stepName": "RAG_INDEXING", "reason": "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"},
    ]
```

- [x] **Step 6: Run, expect failure**

```bash
uv run pytest tests/test_worker_runtime.py::test_degradable_steps_are_skipped_without_step_callbacks -v
```

Expected failure: `ran_steps` contains ALIGNMENT/RAG_INDEXING (runtime executed them via the stub) and step callbacks were sent for them.

- [x] **Step 7: Implement the runtime consult.** In `ai_worker/infrastructure/worker_runtime.py`, change the step loop in `consume_message` to:

```python
        context = self.workflow_engine.start_pipeline(task)
        for step_name in task.pipeline_steps:
            if task.task_type == "SPEAKER_ENROLLMENT" and step_name == "SPEAKER_MATCHING":
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, "NOT_REQUIRED_FOR_ENROLLMENT")
                _add_skipped_step(context, step_name, "NOT_REQUIRED_FOR_ENROLLMENT")
                continue
            skip_reason = self._step_skip_reason(task, step_name)
            if skip_reason is not None:
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, skip_reason)
                _add_skipped_step(context, step_name, skip_reason)
                continue
            result = await self.execute_step(task, step_name, context)
            if result.status == "FAILED":
                if result.error_code == "WRITEBACK_FAILED":
                    await self._fail_for_writeback(task, result.step_name, result.error_message or "callback writeback failed")
                else:
                    await self._fail_for_pipeline_result(task, result)
                return task
```

and add the helper method:

```python
    def _step_skip_reason(self, task: TaskMessage, step_name: str) -> str | None:
        hook = getattr(self.workflow_engine, "step_skip_reason", None)
        if hook is None:
            return None
        return hook(task, step_name)
```

Note: `_add_skipped_step` already dedupes nothing, but the runtime path and the engine-internal path are mutually exclusive (the runtime never calls `run_step` for a skipped step), so no double entries occur.

- [x] **Step 8: Run the full worker-runtime and audio-pipeline suites, expect pass**

```bash
uv run pytest tests/test_worker_runtime.py tests/test_audio_pipeline.py -v
```

Existing tests keep passing because `StubWorkflowEngine` has no `step_skip_reason` attribute (no skips) and 4-/6-step engine tests record no skips (status stays SUCCEEDED).

- [x] **Step 9: Commit**

```bash
git add apps/ai-worker/ai_worker/application/workflows/audio_pipeline.py apps/ai-worker/ai_worker/infrastructure/worker_runtime.py apps/ai-worker/tests/test_audio_pipeline.py apps/ai-worker/tests/test_worker_runtime.py
git commit -m "fix(worker): degrade ALIGNMENT/RAG_INDEXING to skippedSteps + PARTIAL_SUCCEEDED (C1/D3)"
```

---

## Task 4 (C2, D1): Periodic in-flight heartbeats per running step

Replace the single pre-work fake heartbeat (`worker_runtime.py:295-296`, progress=50 before any work, failure fails the whole task) with a cancellable asyncio heartbeat task: every 20s, `status=RUNNING, progress=1` (monotonically non-decreasing, minimum 1 — the worker has no intra-step progress source, so it resends the floor). Heartbeat failures log a warning and never fail the step. The client's idempotency-key logic already routes `RUNNING && progress>0` through the stable heartbeat key (`client.py:359-376`) — unchanged. Lease-owner headers are unchanged (D1: Java stops pre-claiming; the worker's first callback claims the lease).

**Files:**
- Modify: `ai_worker/infrastructure/worker_runtime.py`
- Modify: `tests/test_worker_runtime.py`

- [x] **Step 1: Write the failing tests.** In `tests/test_worker_runtime.py`, add imports at the top:

```python
import asyncio

from ai_worker.domain.task import TaskMessage
```

and add:

```python
def _step_test_task() -> TaskMessage:
    return TaskMessage(
        task_id="task_hb_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        attempt_no=1,
        pipeline_steps=("ASR",),
        trace_id="trace_hb_01",
        meeting_id="mtg_01",
    )


class SlowStepEngine(StubWorkflowEngine):
    def __init__(self, state_store: InMemoryWorkflowStateStore, delay: float) -> None:
        super().__init__(state_store)
        self.delay = delay

    async def run_step(self, context, step_name: str) -> None:
        await asyncio.sleep(self.delay)
        await super().run_step(context, step_name)


@pytest.mark.asyncio
async def test_execute_step_sends_periodic_heartbeats_while_step_runs(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.12)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )
    task = _step_test_task()
    context = engine.start_pipeline(task)

    result = await runtime.execute_step(task, "ASR", context)

    assert result.status == "SUCCEEDED"
    calls = callback_client.update_step.await_args_list
    assert calls[0].kwargs["status"] == "RUNNING" and calls[0].kwargs["progress"] == 0
    heartbeats = [
        c for c in calls
        if c.kwargs["status"] == "RUNNING" and c.kwargs["progress"] >= 1
    ]
    assert len(heartbeats) >= 2
    running_progress = [c.kwargs["progress"] for c in calls if c.kwargs["status"] == "RUNNING"]
    assert running_progress == sorted(running_progress)  # monotonically non-decreasing
    assert calls[-1].kwargs["status"] == "SUCCEEDED" and calls[-1].kwargs["progress"] == 100


@pytest.mark.asyncio
async def test_heartbeat_failure_never_fails_the_step(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.08)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )

    def respond(**kwargs):
        if kwargs["status"] == "RUNNING" and kwargs["progress"] > 0:
            return CallbackResponse(http_status=503, accepted=False, error_code="WRITEBACK_FAILED")
        return CallbackResponse(http_status=200, accepted=True)

    callback_client.update_step.side_effect = respond
    task = _step_test_task()
    context = engine.start_pipeline(task)

    result = await runtime.execute_step(task, "ASR", context)

    assert result.status == "SUCCEEDED"


@pytest.mark.asyncio
async def test_heartbeat_task_is_cancelled_after_step_completes(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.05)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )
    task = _step_test_task()
    context = engine.start_pipeline(task)

    await runtime.execute_step(task, "ASR", context)
    calls_after_step = callback_client.update_step.await_count
    await asyncio.sleep(0.08)

    assert callback_client.update_step.await_count == calls_after_step
```

- [x] **Step 2: Run, expect failure**

```bash
uv run pytest tests/test_worker_runtime.py::test_execute_step_sends_periodic_heartbeats_while_step_runs tests/test_worker_runtime.py::test_heartbeat_failure_never_fails_the_step tests/test_worker_runtime.py::test_heartbeat_task_is_cancelled_after_step_completes -v
```

Expected: `TypeError: MvpWorkerRuntime.__init__() got an unexpected keyword argument 'heartbeat_interval_seconds'`.

- [x] **Step 3: Implement.** In `ai_worker/infrastructure/worker_runtime.py`:

Add `import asyncio` to the imports. Add module constants after `logger = …`:

```python
# D1 locked decision: heartbeat every 20s per running step; progress is
# monotonically non-decreasing with a floor of 1 (the worker has no
# intra-step progress source). TTL stays 120s on the Java side.
HEARTBEAT_INTERVAL_SECONDS = 20.0
HEARTBEAT_MIN_PROGRESS = 1
```

Extend `__init__` signature and body:

```python
    def __init__(
        self,
        callback_client: Any | None = None,
        workflow_engine: Any | None = None,
        embedding_workflow: TextEmbeddingWorkflow | None = None,
        state_store: InMemoryWorkflowStateStore = workflow_state_store,
        heartbeat_interval_seconds: float | None = None,
    ) -> None:
        self.callback_client = callback_client or JavaCallbackClient()
        self.state_store = state_store
        self.heartbeat_interval_seconds = (
            heartbeat_interval_seconds
            if heartbeat_interval_seconds is not None
            else HEARTBEAT_INTERVAL_SECONDS
        )
        # … rest unchanged (workflow_engine / embedding_workflow wiring) …
```

Replace `execute_step` and delete the old `_heartbeat` method:

```python
    async def execute_step(self, task: TaskMessage, step_name: str, context: Any | None = None) -> StepResult:
        started = await self._update_step(task, step_name, "RUNNING", 0)
        if not started.accepted:
            return self._writeback_failed(step_name, "step start callback failed")

        heartbeat_task = asyncio.create_task(self._heartbeat_loop(task, step_name))
        try:
            if context is not None and hasattr(self.workflow_engine, "run_step"):
                await self.workflow_engine.run_step(context, step_name)
        except WorkerPipelineError as exc:
            self.state_store.update_step(task.task_id, step_name, "FAILED", 100, exc.error_code)
            return StepResult(
                step_name=exc.step_name,
                status="FAILED",
                progress=100,
                error_code=exc.error_code,
                error_message=str(exc),
                retryable=exc.retryable,
            )
        finally:
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass

        succeeded = await self._update_step(task, step_name, "SUCCEEDED", 100)
        if not succeeded.accepted:
            return self._writeback_failed(step_name, "step success callback failed")

        return StepResult(step_name=step_name, status="SUCCEEDED", progress=100)

    async def _heartbeat_loop(self, task: TaskMessage, step_name: str) -> None:
        """Send RUNNING(progress>=1) heartbeats forever until cancelled.

        Heartbeat failures are logged and swallowed — they must never fail
        the step (D1). The stable idempotency key in JavaCallbackClient makes
        these latest-wins updates on the Java side (no callback_events rows).
        """
        progress = HEARTBEAT_MIN_PROGRESS
        while True:
            await asyncio.sleep(self.heartbeat_interval_seconds)
            try:
                response = await self.callback_client.update_step(
                    task_id=task.task_id,
                    tenant_id=task.tenant_id,
                    step_name=step_name,
                    attempt_no=task.attempt_no,
                    status="RUNNING",
                    progress=progress,
                    trace_id=task.trace_id,
                    meeting_id=task.meeting_id,
                )
                if response.accepted:
                    self.state_store.update_step(task.task_id, step_name, "RUNNING", progress)
                else:
                    logger.warning(
                        "heartbeat rejected: task_id=%s step=%s http=%s error=%s",
                        task.task_id, step_name, response.http_status, response.error_code,
                    )
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 — heartbeat must never break the step
                logger.warning(
                    "heartbeat failed: task_id=%s step=%s", task.task_id, step_name, exc_info=True
                )
```

Also wrap the embedding path the same way — in `_consume_embedding_message`, replace:

```python
            try:
                await self.embedding_workflow.run_step(context, step_name)
            except WorkerPipelineError as exc:
```

with:

```python
            heartbeat_task = asyncio.create_task(self._heartbeat_loop(task, step_name))
            try:
                await self.embedding_workflow.run_step(context, step_name)
            except WorkerPipelineError as exc:
                # … existing body unchanged …
            finally:
                heartbeat_task.cancel()
                try:
                    await heartbeat_task
                except asyncio.CancelledError:
                    pass
```

(keep the existing `except WorkerPipelineError` body exactly as it is; only add the `heartbeat_task` creation above the `try` and the `finally` block.)

- [x] **Step 4: Update the stale count assertion.** In `tests/test_worker_runtime.py::test_consume_message_submits_java_transcript_version_and_records_workflow` (line 194), change:

```python
    assert callback_client.update_step.await_count == len(_valid_message()["pipelineSteps"]) * 3
```

to:

```python
    # RUNNING(0) + SUCCEEDED(100) per step; periodic heartbeats don't fire for
    # sub-20s fake steps.
    assert callback_client.update_step.await_count == len(_valid_message()["pipelineSteps"]) * 2
```

- [x] **Step 5: Run the full runtime suite, expect pass**

```bash
uv run pytest tests/test_worker_runtime.py -v
```

- [x] **Step 6: Commit**

```bash
git add apps/ai-worker/ai_worker/infrastructure/worker_runtime.py apps/ai-worker/tests/test_worker_runtime.py
git commit -m "fix(worker): periodic 20s in-flight heartbeats, never fatal, cancelled in finally (C2/D1)"
```

---

## Task 5 (C4, D4): Top-level exception safety — every crash reaches Java via /fail

Unexpected exceptions currently destroy the message with no callback: `execute_step` catches only `WorkerPipelineError` (worker_runtime.py:301), and the consumer rejects without requeue (rabbitmq_consumer.py:79-81). Fix at three layers:

1. `execute_step`: generic `except Exception` → `StepResult(FAILED, WORKER_INTERNAL_ERROR, retryable=True)` attributed to the exact step.
2. `consume_message`: wrap dispatch in `try/except WorkerPipelineError / except Exception` → `/fail` with `WORKER_INTERNAL_ERROR` (retryable=true) and never re-raise; extract the audio body into `_consume_audio_message`.
3. `preprocess._metadata_from_ffprobe`: missing audio stream → `AUDIO_CORRUPTED` (instead of `StopIteration`→`RuntimeError`).

**Files:**
- Modify: `ai_worker/infrastructure/worker_runtime.py`
- Modify: `ai_worker/pipeline/audio/preprocess.py`
- Modify: `tests/test_worker_runtime.py`
- Modify: `tests/test_preprocess.py`

- [x] **Step 1: Write the failing tests.** In `tests/test_worker_runtime.py`, add:

```python
class CrashingStepEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        if step_name == "DIARIZATION":
            raise ValueError("unexpected I/O explosion")
        await super().run_step(context, step_name)


class CrashingCompleteEngine(StubWorkflowEngine):
    async def complete_pipeline(self, context):
        raise OSError("disk full while writing manifest")


@pytest.mark.asyncio
async def test_unexpected_step_exception_sends_fail_with_worker_internal_error(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=CrashingStepEngine(state_store),
        state_store=state_store,
    )

    task = await runtime.consume_message(_valid_message())

    assert task is not None  # consume_message must not raise
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["failed_step"] == "DIARIZATION"
    assert fail_kwargs["error_code"] == "WORKER_INTERNAL_ERROR"
    assert fail_kwargs["retryable"] is True
    callback_client.complete_worker_phase.assert_not_awaited()


@pytest.mark.asyncio
async def test_unexpected_completion_exception_sends_fail_with_worker_internal_error(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=CrashingCompleteEngine(state_store),
        state_store=state_store,
    )

    task = await runtime.consume_message(_valid_message())

    assert task is not None
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["error_code"] == "WORKER_INTERNAL_ERROR"
    assert fail_kwargs["retryable"] is True
    # No step was executing — attribute to the last pipeline step.
    assert fail_kwargs["failed_step"] == "RAG_INDEXING"
```

In `tests/test_preprocess.py`, add:

```python
from ai_worker.pipeline.audio.preprocess import _metadata_from_ffprobe


def test_metadata_from_ffprobe_maps_missing_audio_stream_to_audio_corrupted() -> None:
    payload = {
        "streams": [{"codec_type": "video", "codec_name": "h264"}],
        "format": {"duration": "1.0"},
    }
    with pytest.raises(AudioPreprocessError) as exc_info:
        _metadata_from_ffprobe(payload)
    assert exc_info.value.error_code == "AUDIO_CORRUPTED"
```

- [x] **Step 2: Run, expect failures**

```bash
uv run pytest tests/test_worker_runtime.py::test_unexpected_step_exception_sends_fail_with_worker_internal_error tests/test_worker_runtime.py::test_unexpected_completion_exception_sends_fail_with_worker_internal_error tests/test_preprocess.py::test_metadata_from_ffprobe_maps_missing_audio_stream_to_audio_corrupted -v
```

Expected: the first two raise `ValueError`/`OSError` straight out of `consume_message`; the third raises `StopIteration` instead of `AudioPreprocessError`.

- [x] **Step 3: Implement the preprocess fix.** In `ai_worker/pipeline/audio/preprocess.py`, replace `_metadata_from_ffprobe` lines 104-107:

```python
def _metadata_from_ffprobe(payload: dict[str, Any]) -> AudioMetadata:
    audio_stream = next(
        (
            stream
            for stream in payload.get("streams", [])
            if stream.get("codec_type") == "audio"
        ),
        None,
    )
    if audio_stream is None:
        raise AudioPreprocessError("AUDIO_CORRUPTED", "no audio stream found in container")
    format_info = payload.get("format", {})
    # … rest unchanged …
```

- [x] **Step 4: Implement the runtime guards.** In `ai_worker/infrastructure/worker_runtime.py`:

(a) Add a generic except to `execute_step` (between the `WorkerPipelineError` handler and `finally` from Task 4):

```python
        except WorkerPipelineError as exc:
            self.state_store.update_step(task.task_id, step_name, "FAILED", 100, exc.error_code)
            return StepResult(
                step_name=exc.step_name,
                status="FAILED",
                progress=100,
                error_code=exc.error_code,
                error_message=str(exc),
                retryable=exc.retryable,
            )
        except Exception as exc:  # noqa: BLE001 — D4: attribute unexpected step crashes precisely
            logger.exception(
                "WORKER_INTERNAL_ERROR in step: task_id=%s step=%s", task.task_id, step_name
            )
            self.state_store.update_step(task.task_id, step_name, "FAILED", 100, "WORKER_INTERNAL_ERROR")
            return StepResult(
                step_name=step_name,
                status="FAILED",
                progress=100,
                error_code="WORKER_INTERNAL_ERROR",
                error_message=f"{type(exc).__name__}: {exc}",
                retryable=True,
            )
        finally:
            heartbeat_task.cancel()
            ...
```

(b) Split `consume_message`: rename the existing body (everything from `context = self.workflow_engine.start_pipeline(task)` to the final `return task`, including the Task 3 skip logic) into a new method `_consume_audio_message(self, task: TaskMessage) -> TaskMessage`, and make `consume_message` the guarded dispatcher:

```python
    async def consume_message(self, raw_message: dict[str, Any]) -> TaskMessage | None:
        task = await consume_and_validate(raw_message, self.callback_client)
        if task is None:
            return None

        try:
            if is_embedding_task(task):
                return await self._consume_embedding_message(task)
            return await self._consume_audio_message(task)
        except WorkerPipelineError as exc:
            # Raised outside execute_step (start_pipeline / complete_pipeline /
            # submit helpers) — same handling as a failed step result.
            await self._fail_for_pipeline_result(
                task,
                StepResult(
                    step_name=exc.step_name,
                    status="FAILED",
                    progress=100,
                    error_code=exc.error_code,
                    error_message=str(exc),
                    retryable=exc.retryable,
                ),
            )
            return task
        except Exception as exc:  # noqa: BLE001 — D4: Java must learn about every crash before ack/reject
            logger.exception("WORKER_INTERNAL_ERROR: task_id=%s", task.task_id)
            self.state_store.fail(task.task_id, "WORKER_INTERNAL_ERROR", str(exc))
            kwargs: dict[str, Any] = {
                "task_id": task.task_id,
                "tenant_id": task.tenant_id,
                "attempt_no": task.attempt_no,
                "failed_step": task.pipeline_steps[-1] if task.pipeline_steps else "AUDIO_PREPROCESS",
                "error_code": "WORKER_INTERNAL_ERROR",
                "error_message": f"{type(exc).__name__}: {exc}",
                "retryable": True,
                "trace_id": task.trace_id,
                "meeting_id": task.meeting_id,
            }
            if speaker_enrollment_id := _speaker_enrollment_id_for_task(task):
                kwargs["speaker_enrollment_id"] = speaker_enrollment_id
            await self.callback_client.fail_task(**kwargs)
            return task

    async def _consume_audio_message(self, task: TaskMessage) -> TaskMessage:
        context = self.workflow_engine.start_pipeline(task)
        for step_name in task.pipeline_steps:
            if task.task_type == "SPEAKER_ENROLLMENT" and step_name == "SPEAKER_MATCHING":
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, "NOT_REQUIRED_FOR_ENROLLMENT")
                _add_skipped_step(context, step_name, "NOT_REQUIRED_FOR_ENROLLMENT")
                continue
            skip_reason = self._step_skip_reason(task, step_name)
            if skip_reason is not None:
                self.state_store.update_step(task.task_id, step_name, "SKIPPED", 100, skip_reason)
                _add_skipped_step(context, step_name, skip_reason)
                continue
            result = await self.execute_step(task, step_name, context)
            if result.status == "FAILED":
                if result.error_code == "WRITEBACK_FAILED":
                    await self._fail_for_writeback(task, result.step_name, result.error_message or "callback writeback failed")
                else:
                    await self._fail_for_pipeline_result(task, result)
                return task

        artifact = await self.workflow_engine.complete_pipeline(context)
        # … remainder of the original consume_message body unchanged
        #    (enrollment submission, speaker candidates, transcript,
        #    complete_worker_phase, state_store.complete) …
        return task
```

`_consume_audio_message` keeps the original code verbatim from `artifact = await self.workflow_engine.complete_pipeline(context)` down to `self.state_store.complete(task.task_id, artifact.terminal_status)` / `return task`.

Note: `JavaCallbackClient.fail_task` never raises (all transport errors are swallowed into `CallbackResponse`), so the guard cannot recurse.

- [x] **Step 5: Run, expect pass; verify no regressions**

```bash
uv run pytest tests/test_worker_runtime.py tests/test_preprocess.py tests/test_audio_pipeline.py -v
```

- [x] **Step 6: Commit**

```bash
git add apps/ai-worker/ai_worker/infrastructure/worker_runtime.py apps/ai-worker/ai_worker/pipeline/audio/preprocess.py apps/ai-worker/tests/test_worker_runtime.py apps/ai-worker/tests/test_preprocess.py
git commit -m "fix(worker): top-level exception guard sends /fail WORKER_INTERNAL_ERROR; ffprobe no-audio-stream -> AUDIO_CORRUPTED (C4/D4)"
```

---

## Task 6 (C3, D2): Stop starving the pika connection — run pipelines on a worker thread

`_on_message` currently blocks the connection thread with `asyncio.run(...)` (rabbitmq_consumer.py:71-75) while `heartbeat=30` (line 49); any task > ~60s gets the connection closed and the message redelivered → duplicate pipeline execution. Locked decision D2: fix in place — pipeline executes on a worker thread, the connection thread keeps servicing I/O via `start_consuming()`, ack/reject are marshalled back with `connection.add_callback_threadsafe(...)`. `prefetch_count=1` guarantees a single in-flight message.

**Files:**
- Modify: `ai_worker/infrastructure/mq/rabbitmq_consumer.py`
- Modify: `tests/test_rabbitmq_consumer.py`
- Modify: `apps/ai-worker/SPEC.md`

- [x] **Step 1: Rewrite the consumer tests (they currently pin the blocking behavior).** Replace the body of `tests/test_rabbitmq_consumer.py` with:

```python
from __future__ import annotations

import asyncio
import json
import threading
from unittest.mock import AsyncMock

from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer


class _Method:
    delivery_tag = "delivery_01"


class _Channel:
    def __init__(self) -> None:
        self.acked: list[str] = []
        self.rejected: list[tuple[str, bool]] = []

    def basic_ack(self, delivery_tag: str) -> None:
        self.acked.append(delivery_tag)

    def basic_reject(self, delivery_tag: str, requeue: bool) -> None:
        self.rejected.append((delivery_tag, requeue))


class _FakeConnection:
    """Collects add_callback_threadsafe callables like pika's ioloop would."""

    def __init__(self) -> None:
        self.callbacks: list = []
        self.is_open = True

    def add_callback_threadsafe(self, fn) -> None:
        self.callbacks.append(fn)

    def drain(self) -> None:
        while self.callbacks:
            self.callbacks.pop(0)()


def _runtime_mock() -> AsyncMock:
    runtime = AsyncMock()
    runtime.oom_exit_requested = False
    return runtime


def test_process_message_acks_via_threadsafe_callback() -> None:
    runtime = _runtime_mock()
    consumer = RabbitMqTaskConsumer(runtime)
    connection = _FakeConnection()
    consumer._connection = connection
    channel = _Channel()

    consumer._process_message(channel, "delivery_01", json.dumps({"taskId": "task_01"}).encode())

    runtime.consume_message.assert_awaited_once_with({"taskId": "task_01"})
    assert channel.acked == []  # never acked inline from the worker thread
    connection.drain()
    assert channel.acked == ["delivery_01"]
    assert channel.rejected == []


def test_process_message_rejects_invalid_json_without_requeue() -> None:
    runtime = _runtime_mock()
    consumer = RabbitMqTaskConsumer(runtime)
    connection = _FakeConnection()
    consumer._connection = connection
    channel = _Channel()

    consumer._process_message(channel, "delivery_01", b"{bad")

    runtime.consume_message.assert_not_called()
    connection.drain()
    assert channel.acked == []
    assert channel.rejected == [("delivery_01", False)]


def test_process_message_rejects_when_runtime_raises() -> None:
    runtime = _runtime_mock()
    runtime.consume_message.side_effect = RuntimeError("boom")
    consumer = RabbitMqTaskConsumer(runtime)
    connection = _FakeConnection()
    consumer._connection = connection
    channel = _Channel()

    consumer._process_message(channel, "delivery_01", json.dumps({"taskId": "task_01"}).encode())

    connection.drain()
    assert channel.rejected == [("delivery_01", False)]


def test_on_message_does_not_block_the_connection_thread() -> None:
    started = threading.Event()
    release = threading.Event()

    class _BlockingRuntime:
        oom_exit_requested = False

        async def consume_message(self, raw_message):
            started.set()
            await asyncio.get_running_loop().run_in_executor(None, release.wait)
            return None

    consumer = RabbitMqTaskConsumer(_BlockingRuntime())
    connection = _FakeConnection()
    consumer._connection = connection
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "task_01"}).encode())

    # _on_message returned while the pipeline is still running on the worker thread.
    assert started.wait(timeout=2.0)
    assert channel.acked == [] and channel.rejected == []
    release.set()
    assert consumer._in_flight is not None
    consumer._in_flight.join(timeout=2.0)
    connection.drain()
    assert channel.acked == ["delivery_01"]
```

- [x] **Step 2: Run, expect failure**

```bash
uv run pytest tests/test_rabbitmq_consumer.py -v
```

Expected: `AttributeError: 'RabbitMqTaskConsumer' object has no attribute '_process_message'` / `_in_flight`.

- [x] **Step 3: Implement.** Replace `RabbitMqTaskConsumer` in `ai_worker/infrastructure/mq/rabbitmq_consumer.py`:

```python
from __future__ import annotations

import asyncio
import json
import logging
import threading
from dataclasses import dataclass
from typing import Any, Callable

import pika

from ai_worker.common.config import settings
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.observability.gpu_metrics import report_oom_and_exit

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RabbitMqConsumerConfig:
    host: str = settings.rabbitmq_host
    port: int = settings.rabbitmq_port
    username: str = settings.rabbitmq_username
    password: str = settings.rabbitmq_password
    virtual_host: str = settings.rabbitmq_virtual_host
    queues: tuple[str, ...] = tuple(
        queue.strip()
        for queue in settings.rabbitmq_task_queues.split(",")
        if queue.strip()
    )


class RabbitMqTaskConsumer:
    """Pipeline execution runs on a worker thread (D2): the delivery callback
    returns immediately so the BlockingConnection thread keeps servicing
    broker heartbeats inside start_consuming(); ack/reject are marshalled
    back via connection.add_callback_threadsafe. prefetch_count=1 keeps a
    single in-flight message, so one worker thread is enough.
    """

    def __init__(
        self,
        runtime: MvpWorkerRuntime,
        config: RabbitMqConsumerConfig | None = None,
    ) -> None:
        self.runtime = runtime
        self.config = config or RabbitMqConsumerConfig()
        self._connection: pika.BlockingConnection | None = None
        self._channel: Any | None = None
        self._in_flight: threading.Thread | None = None

    def start_consuming(self) -> None:
        credentials = pika.PlainCredentials(self.config.username, self.config.password)
        parameters = pika.ConnectionParameters(
            host=self.config.host,
            port=self.config.port,
            virtual_host=self.config.virtual_host,
            credentials=credentials,
            heartbeat=30,
            blocked_connection_timeout=30,
        )
        self._connection = pika.BlockingConnection(parameters)
        channel = self._connection.channel()
        self._channel = channel
        channel.basic_qos(prefetch_count=1)
        for queue in self.config.queues:
            channel.basic_consume(
                queue=queue,
                on_message_callback=self._on_message,
                auto_ack=False,
            )
        logger.info("RabbitMQ task consumer started for queues=%s", self.config.queues)
        channel.start_consuming()

    def stop(self) -> None:
        if self._channel and self._channel.is_open:
            self._channel.stop_consuming()
        in_flight = self._in_flight
        if in_flight is not None and in_flight.is_alive():
            in_flight.join(timeout=30.0)
        if self._connection and self._connection.is_open:
            self._connection.close()

    def _on_message(self, channel: Any, method: Any, _properties: Any, body: bytes) -> None:
        worker = threading.Thread(
            target=self._process_message,
            args=(channel, method.delivery_tag, body),
            name=f"task-{method.delivery_tag}",
            daemon=True,
        )
        self._in_flight = worker
        worker.start()

    def _process_message(self, channel: Any, delivery_tag: Any, body: bytes) -> None:
        try:
            raw_message = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            logger.exception("invalid JSON task message; rejecting without requeue")
            self._dispatch_threadsafe(
                lambda: channel.basic_reject(delivery_tag=delivery_tag, requeue=False)
            )
            return

        acked = False
        try:
            asyncio.run(self.runtime.consume_message(raw_message))
            acked = True
        except Exception:  # noqa: BLE001 — last resort; runtime's D4 guard normally reports /fail first
            logger.exception("task message failed; rejecting without requeue")
        if acked:
            self._dispatch_threadsafe(lambda: channel.basic_ack(delivery_tag=delivery_tag))
        else:
            self._dispatch_threadsafe(
                lambda: channel.basic_reject(delivery_tag=delivery_tag, requeue=False)
            )
        # D10: OOM exit is scheduled AFTER the ack/reject callback so the broker
        # sees the delivery settled before the process dies. `is True` guards
        # against Mock truthiness in tests.
        if getattr(self.runtime, "oom_exit_requested", False) is True:
            self._dispatch_threadsafe(report_oom_and_exit)

    def _dispatch_threadsafe(self, fn: Callable[[], None]) -> None:
        connection = self._connection
        if connection is not None and getattr(connection, "is_open", False):
            connection.add_callback_threadsafe(fn)
        else:
            # No live connection (unit tests / already closed): best-effort direct call.
            fn()
```

- [x] **Step 4: Run, expect pass**

```bash
uv run pytest tests/test_rabbitmq_consumer.py -v
```

- [x] **Step 5: Amend SPEC.md (D2 doc note).** In `apps/ai-worker/SPEC.md`, immediately after the `Prefect 3.x WorkflowEngine` line in §2 (技术栈), insert:

```markdown
> **Remediation note (2026-06-12, review P1):** Dramatiq WorkerRuntime 与 Prefect
> WorkflowEngine 在本阶段仍**保持延期**。当前生产消费实现是 pika `BlockingConnection`：
> 投递回调把任务交给 worker 线程执行，连接线程持续处理 broker 心跳，ack/reject 通过
> `connection.add_callback_threadsafe(...)` 回投（见
> `ai_worker/infrastructure/mq/rabbitmq_consumer.py`）。Dramatiq/Prefect 迁移在
> Phase J 验收后再评估；不要将 §2 / §8.1 的 actor 拓扑当作已实现。
```

- [x] **Step 6: Commit**

```bash
git add apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py apps/ai-worker/tests/test_rabbitmq_consumer.py apps/ai-worker/SPEC.md
git commit -m "fix(worker): execute pipeline on worker thread, threadsafe ack/reject; record Dramatiq/Prefect deferral (C3/D2)"
```

---

## Task 7 (I9, D9): Send the artifacts callback and a real artifactManifestId

`JavaCallbackClient.submit_artifacts` (client.py:243-262) has zero callers, and `submit_transcript` is called with `artifact_manifest_id=None` while smuggling the manifest URI through `metadata.artifactManifestUri` (worker_runtime.py:158-163). Contract shape (`internal-callback-api.yaml` → `ArtifactCallbackRequest`): `{tenantId, meetingId?, taskId, attemptNo, artifacts: [{artifactType, artifactUri (^tos://), sha256, sizeBytes?, metadata?}], artifactManifestId?}`. The Java-side P2 plan implements persistence; the endpoint accepts this shape.

Changes:
1. `_artifact_dict` emits `artifactType` (contract key) instead of `category` — the manifest JSON content changes accordingly.
2. `PipelineArtifact` splits `artifact_manifest_id` (logical `artifact_manifest_{taskId}_{attemptNo}`) from a new `artifact_manifest_uri` (tos:// location); `complete_pipeline` also appends an `ARTIFACT_MANIFEST` entry to `context.artifacts` so Java receives the manifest location.
3. `MvpWorkerRuntime._consume_audio_message` POSTs `/artifacts` right after `complete_pipeline` (when the context carries artifacts) and passes the logical id to `submit_transcript`; the metadata smuggle is removed.

**Files:**
- Modify: `ai_worker/domain/task/models.py`
- Modify: `ai_worker/application/workflows/audio_pipeline.py`
- Modify: `ai_worker/infrastructure/worker_runtime.py`
- Modify: `tests/test_audio_pipeline.py`
- Modify: `tests/test_worker_runtime.py`

- [x] **Step 1: Write the failing tests.** In `tests/test_worker_runtime.py`: first add `client.submit_artifacts.return_value = CallbackResponse(http_status=200, accepted=True)` to the `callback_client` fixture, then add:

```python
class ArtifactReportingEngine(StubWorkflowEngine):
    def start_pipeline(self, task):
        context = super().start_pipeline(task)
        context["artifacts"] = [
            {
                "artifactType": "TRANSCRIPT_MERGE",
                "artifactUri": "tos://meeting-artifacts/transcript-merge.json",
                "sha256": "a" * 64,
                "sizeBytes": 123,
            },
        ]
        return context

    async def complete_pipeline(self, context):
        base = await super().complete_pipeline(context)
        return PipelineArtifact(
            task_id=base.task_id,
            transcript_segments=base.transcript_segments,
            speaker_candidates=base.speaker_candidates,
            artifact_manifest_id="artifact_manifest_task_runtime_01_1",
            artifact_manifest_uri="tos://meeting-artifacts/manifest.json",
            terminal_status="SUCCEEDED",
        )


@pytest.mark.asyncio
async def test_artifacts_callback_is_sent_and_transcript_carries_manifest_id(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = ArtifactReportingEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)

    await runtime.consume_message(_valid_message())

    callback_client.submit_artifacts.assert_awaited_once()
    artifacts_kwargs = callback_client.submit_artifacts.await_args.kwargs
    assert artifacts_kwargs["artifact_manifest_id"] == "artifact_manifest_task_runtime_01_1"
    assert artifacts_kwargs["artifacts"][0]["artifactType"] == "TRANSCRIPT_MERGE"
    assert artifacts_kwargs["artifacts"][0]["artifactUri"].startswith("tos://")

    transcript_kwargs = callback_client.submit_transcript.await_args.kwargs
    assert transcript_kwargs["artifact_manifest_id"] == "artifact_manifest_task_runtime_01_1"
    assert "artifactManifestUri" not in transcript_kwargs["metadata"]


@pytest.mark.asyncio
async def test_artifacts_callback_failure_records_writeback_failed(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = ArtifactReportingEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    callback_client.submit_artifacts.return_value = CallbackResponse(
        http_status=503, accepted=False, error_code="WRITEBACK_FAILED"
    )

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_awaited_once()
    assert callback_client.fail_task.await_args.kwargs["error_code"] == "WRITEBACK_FAILED"
    callback_client.complete_worker_phase.assert_not_awaited()
```

In `tests/test_audio_pipeline.py::test_local_audio_pipeline_writes_artifacts_and_transcript`, update the assertions (lines 88-98):

```python
    assert artifact.terminal_status == "SUCCEEDED"
    assert artifact.artifact_manifest_id == "artifact_manifest_task_audio_01_1"
    assert artifact.artifact_manifest_uri is not None
    manifest = await store.download_json(artifact.artifact_manifest_uri)
    assert manifest["pipelineVersion"] == "phase2-local-v1"
    assert manifest["artifactManifestId"] == "artifact_manifest_task_audio_01_1"
    assert [item["artifactType"] for item in manifest["artifacts"]] == [
        "QUALITY_REPORT",
        "ASR_RAW",
        "DIARIZATION_TURNS",
        "TRANSCRIPT_MERGE",
    ]
```

- [x] **Step 2: Run, expect failures**

```bash
uv run pytest tests/test_worker_runtime.py::test_artifacts_callback_is_sent_and_transcript_carries_manifest_id tests/test_worker_runtime.py::test_artifacts_callback_failure_records_writeback_failed tests/test_audio_pipeline.py::test_local_audio_pipeline_writes_artifacts_and_transcript -v
```

- [x] **Step 3: Implement.**

(a) `ai_worker/domain/task/models.py` — extend `PipelineArtifact`:

```python
@dataclass(frozen=True)
class PipelineArtifact:
    task_id: str
    transcript_segments: list[dict[str, Any]] = field(default_factory=list)
    speaker_candidates: list[dict[str, Any]] = field(default_factory=list)
    artifact_manifest_id: str | None = None
    artifact_manifest_uri: str | None = None
    terminal_status: str = "SUCCEEDED"
```

(b) `ai_worker/application/workflows/audio_pipeline.py` — rename the dict key and split id/uri:

```python
def _artifact_dict(artifact_type: str, uri: str, sha256: str, size_bytes: int | None) -> dict[str, Any]:
    # Key names match ArtifactCallbackRequest in internal-callback-api.yaml.
    return {
        "artifactType": artifact_type,
        "artifactUri": uri,
        "sha256": sha256,
        "sizeBytes": size_bytes,
    }
```

Update `complete_pipeline` (building on Task 3's version):

```python
    async def complete_pipeline(self, context: "_PipelineContext") -> PipelineArtifact:
        manifest_id = f"artifact_manifest_{context.task.task_id}_{context.task.attempt_no}"
        manifest_ref = await self._write_manifest(context, manifest_id)
        context.artifacts.append(
            _artifact_dict("ARTIFACT_MANIFEST", manifest_ref.uri, manifest_ref.sha256, manifest_ref.size_bytes)
        )
        degraded = any(
            s.get("stepName") in DEGRADED_SKIP_STEPS for s in context.skipped_steps
        )
        return PipelineArtifact(
            task_id=context.task.task_id,
            transcript_segments=context.transcript_segments,
            speaker_candidates=context.speaker_candidates,
            artifact_manifest_id=manifest_id,
            artifact_manifest_uri=manifest_ref.uri,
            terminal_status="PARTIAL_SUCCEEDED" if degraded else "SUCCEEDED",
        )
```

and `_write_manifest` takes the id (replace the inline f-string):

```python
    async def _write_manifest(self, context: "_PipelineContext", manifest_id: str) -> Any:
        task = context.task
        manifest = {
            "artifactManifestId": manifest_id,
            # … rest of the dict unchanged …
        }
        return await self._write_json_artifact(task, "manifest", "artifact-manifest.json", manifest)
```

(c) `ai_worker/infrastructure/worker_runtime.py` — in `_consume_audio_message`, right after `artifact = await self.workflow_engine.complete_pipeline(context)`, insert:

```python
        artifacts_payload = _artifacts_from_context(context)
        if artifacts_payload:
            artifacts_response = await self.callback_client.submit_artifacts(
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                attempt_no=task.attempt_no,
                artifacts=artifacts_payload,
                artifact_manifest_id=artifact.artifact_manifest_id,
                trace_id=task.trace_id,
            )
            if not artifacts_response.accepted:
                await self._fail_for_writeback(task, task.pipeline_steps[-1], "artifacts callback failed")
                return task
```

Update the transcript call (remove the smuggle, pass the id):

```python
            transcript_response = await self.callback_client.submit_transcript(
                task_id=task.task_id,
                tenant_id=task.tenant_id,
                meeting_id=task.meeting_id,
                attempt_no=task.attempt_no,
                transcript_version=_transcript_version_for_task(task),
                segments=artifact.transcript_segments,
                metadata={
                    "workflowId": f"wf_{task.task_id}_{task.attempt_no}",
                    "mode": "phase2-local",
                },
                artifact_manifest_id=artifact.artifact_manifest_id,
                trace_id=task.trace_id,
            )
```

Add the module-level helper (next to `_skipped_steps_from_context`):

```python
def _artifacts_from_context(context: Any) -> list[dict[str, Any]]:
    if isinstance(context, dict):
        artifacts = context.get("artifacts", [])
    else:
        artifacts = getattr(context, "artifacts", [])
    if not isinstance(artifacts, list):
        return []
    return [a for a in artifacts if isinstance(a, dict)]
```

- [x] **Step 4: Run, expect pass**

```bash
uv run pytest tests/test_worker_runtime.py tests/test_audio_pipeline.py -v
```

Note: `StubWorkflowEngine`'s dict context has no `artifacts` key, so existing tests skip the artifacts callback; enrollment tasks now also report their manifest entry (harmless — contract allows it).

- [x] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/domain/task/models.py apps/ai-worker/ai_worker/application/workflows/audio_pipeline.py apps/ai-worker/ai_worker/infrastructure/worker_runtime.py apps/ai-worker/tests/test_audio_pipeline.py apps/ai-worker/tests/test_worker_runtime.py
git commit -m "fix(worker): send /artifacts callback and real artifactManifestId on /transcript (I9/D9)"
```

---

## Task 8 (I8, D10): CUDA OOM — stable codes, /fail, then process exit

`report_oom_and_exit()` (gpu_metrics.py:166-178) has zero call sites; `_transcribe_blocking` swallows `torch.cuda.OutOfMemoryError` into retryable `ASR_RUNTIME_ERROR` (qwen3_asr_runtime.py:220-224); same blanket catches in `pyannote_runtime.py:178` and `cam_plus_plus_runtime.py:180`. SPEC §8 (line 285) requires the worker to exit after OOM. Flow: blocking inference path detects OOM → raises the step error with a `*_GPU_OOM` registered code (retryable=true) → normal `/fail` path runs → `MvpWorkerRuntime` sets `oom_exit_requested` → the consumer (Task 6) schedules `report_oom_and_exit()` after the ack.

**Files:**
- Modify: `ai_worker/observability/gpu_metrics.py`
- Modify: `ai_worker/model_runtime/asr/qwen3_asr_runtime.py`
- Modify: `ai_worker/model_runtime/diarization/pyannote_runtime.py`
- Modify: `ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py`
- Modify: `ai_worker/infrastructure/worker_runtime.py`
- Create: `tests/test_gpu_oom.py`
- Modify: `tests/test_rabbitmq_consumer.py`

- [x] **Step 1: Write the failing tests.** Create `tests/test_gpu_oom.py`:

```python
from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.audio_pipeline import WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.model_runtime.asr.qwen3_asr_runtime import Qwen3AsrRuntime, Qwen3AsrRuntimeError
from ai_worker.observability.gpu_metrics import is_cuda_oom
from ai_worker.pipeline.audio.preprocess import AudioMetadata

from tests.test_worker_runtime import StubWorkflowEngine, _valid_message


def _fake_oom_class() -> type[Exception]:
    klass = type("OutOfMemoryError", (RuntimeError,), {})
    klass.__module__ = "torch.cuda"
    return klass


def test_is_cuda_oom_matches_torch_oom_without_importing_torch() -> None:
    FakeOom = _fake_oom_class()
    assert is_cuda_oom(FakeOom("CUDA out of memory"))
    assert not is_cuda_oom(RuntimeError("CUDA out of memory"))
    assert not is_cuda_oom(MemoryError("host oom"))


def test_qwen3_transcribe_blocking_maps_cuda_oom_to_asr_gpu_oom() -> None:
    runtime = Qwen3AsrRuntime(use_fake=False, models_dir=None, device="cpu")
    FakeOom = _fake_oom_class()

    class _Model:
        def generate(self, **kwargs):
            raise FakeOom("CUDA out of memory. Tried to allocate 2.00 GiB")

    runtime._model = _Model()
    metadata = AudioMetadata(
        duration_ms=1_000, sample_rate_hz=16_000, channels=1,
        codec="pcm_s16le", bitrate=None, format_name="wav",
    )

    with pytest.raises(Qwen3AsrRuntimeError) as exc_info:
        runtime._transcribe_blocking(Path("x.wav"), metadata, "zh")

    assert exc_info.value.error_code == "ASR_GPU_OOM"


class _OomStepEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        raise WorkerPipelineError("ASR", "ASR_GPU_OOM", "CUDA out of memory", retryable=True)


@pytest.mark.asyncio
async def test_oom_pipeline_failure_sends_fail_then_requests_worker_exit() -> None:
    callback_client = AsyncMock()
    callback_client.update_step.return_value = CallbackResponse(http_status=200, accepted=True)
    callback_client.fail_task.return_value = CallbackResponse(http_status=200, accepted=True)
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=_OomStepEngine(state_store),
        state_store=state_store,
    )
    assert runtime.oom_exit_requested is False

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["error_code"] == "ASR_GPU_OOM"
    assert fail_kwargs["retryable"] is True
    assert runtime.oom_exit_requested is True
```

Add to `tests/test_rabbitmq_consumer.py`:

```python
def test_consumer_schedules_oom_exit_after_ack(monkeypatch) -> None:
    exits: list[bool] = []
    monkeypatch.setattr(
        "ai_worker.infrastructure.mq.rabbitmq_consumer.report_oom_and_exit",
        lambda: exits.append(True),
    )
    runtime = _runtime_mock()
    runtime.oom_exit_requested = True
    consumer = RabbitMqTaskConsumer(runtime)
    connection = _FakeConnection()
    consumer._connection = connection
    channel = _Channel()

    consumer._process_message(channel, "delivery_01", json.dumps({"taskId": "task_01"}).encode())
    connection.drain()

    assert channel.acked == ["delivery_01"]
    assert exits == [True]
```

- [x] **Step 2: Run, expect failures**

```bash
uv run pytest tests/test_gpu_oom.py tests/test_rabbitmq_consumer.py::test_consumer_schedules_oom_exit_after_ack -v
```

Expected: `ImportError: cannot import name 'is_cuda_oom'`; `oom_exit_requested` AttributeError.

- [x] **Step 3: Implement the OOM detector.** In `ai_worker/observability/gpu_metrics.py`, add after `record_step_failure`:

```python
def is_cuda_oom(exc: BaseException) -> bool:
    """True when ``exc`` is torch.cuda.OutOfMemoryError (or the torch>=2.5
    alias torch.OutOfMemoryError) — detected structurally so fake-mode
    processes never import torch and tests can synthesize the class."""
    for klass in type(exc).__mro__:
        if klass.__name__ == "OutOfMemoryError" and klass.__module__.split(".")[0] == "torch":
            return True
    return False
```

- [x] **Step 4: Map OOM in the three blocking inference paths.**

(a) `qwen3_asr_runtime.py` `_transcribe_blocking` — replace the except block (lines 220-224):

```python
        except Exception as exc:
            from ai_worker.observability.gpu_metrics import is_cuda_oom

            if is_cuda_oom(exc):
                raise Qwen3AsrRuntimeError(
                    "ASR_GPU_OOM",
                    f"qwen3-asr CUDA OOM: {exc}",
                ) from exc
            raise Qwen3AsrRuntimeError(
                "ASR_RUNTIME_ERROR",
                f"qwen3-asr inference failed: {exc}",
            ) from exc
```

(b) `pyannote_runtime.py` `_diarize_blocking` — replace its `except Exception` (line ~178):

```python
        except Exception as exc:
            from ai_worker.observability.gpu_metrics import is_cuda_oom

            if is_cuda_oom(exc):
                raise PyannoteDiarizationRuntimeError(
                    "DIARIZATION_GPU_OOM",
                    f"pyannote CUDA OOM: {exc}",
                ) from exc
            raise PyannoteDiarizationRuntimeError(
                "DIARIZATION_FAILED",
                f"pyannote inference failed: {exc}",
            ) from exc
```

(c) `cam_plus_plus_runtime.py` `_embed_blocking` — change the trailing handler (line ~180) to re-raise its own error type first, then detect OOM:

```python
        except CamPlusPlusRuntimeError:
            raise
        except Exception as exc:
            from ai_worker.observability.gpu_metrics import is_cuda_oom

            if is_cuda_oom(exc):
                raise CamPlusPlusRuntimeError(
                    "SPEAKER_EMBEDDING_GPU_OOM",
                    f"CAM++ CUDA OOM: {exc}",
                ) from exc
            raise CamPlusPlusRuntimeError(
                "SPEAKER_EMBEDDING_FAILED",
                f"CAM++ inference failed: {exc}",
            ) from exc
```

- [x] **Step 5: Set the exit flag in the runtime.** In `ai_worker/infrastructure/worker_runtime.py`:

Add a module constant after `HEARTBEAT_MIN_PROGRESS`:

```python
OOM_ERROR_CODES = frozenset({"ASR_GPU_OOM", "DIARIZATION_GPU_OOM", "SPEAKER_EMBEDDING_GPU_OOM"})
```

In `__init__`, add `self.oom_exit_requested = False`. At the end of `_fail_for_pipeline_result` (after `await self.callback_client.fail_task(**kwargs)`):

```python
        if error_code in OOM_ERROR_CODES:
            # D10 / SPEC §8: never keep consuming on an unknown VRAM state.
            # The consumer schedules report_oom_and_exit() after settling the delivery.
            self.oom_exit_requested = True
```

(The consumer-side check `if getattr(self.runtime, "oom_exit_requested", False) is True` was already added in Task 6.)

- [x] **Step 6: Run, expect pass**

```bash
uv run pytest tests/test_gpu_oom.py tests/test_rabbitmq_consumer.py tests/test_worker_runtime.py -v
```

- [x] **Step 7: Commit**

```bash
git add apps/ai-worker/ai_worker/observability/gpu_metrics.py apps/ai-worker/ai_worker/model_runtime/asr/qwen3_asr_runtime.py apps/ai-worker/ai_worker/model_runtime/diarization/pyannote_runtime.py apps/ai-worker/ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py apps/ai-worker/ai_worker/infrastructure/worker_runtime.py apps/ai-worker/tests/test_gpu_oom.py apps/ai-worker/tests/test_rabbitmq_consumer.py
git commit -m "fix(worker): map CUDA OOM to registered *_GPU_OOM codes, /fail then report_oom_and_exit (I8/D10)"
```

---

## Task 9 (I5, D6): Rerank scores ALL candidates before slicing topN

`main.py:918` truncates to `topN` before scoring; the order-preserving fake hid it. Fix: score all candidates (request cap is 50), reuse the existing sort (score desc, input-index tie-break), then slice.

**Files:**
- Modify: `ai_worker/interfaces/api/main.py` (rerank handler)
- Modify: `tests/test_rerank.py`

- [x] **Step 1: Write the failing test.** Add to `tests/test_rerank.py`:

```python
class _ScriptedRerankRuntime:
    """Real-mode style runtime returning non-monotonic scores (D6)."""

    model_version = "scripted-rerank-v1"
    status = "READY"
    last_error = None
    device = "fake"
    use_fake = True

    async def ensure_loaded(self) -> None:
        return None

    async def arank(self, query: str, texts: list[str]) -> list[float]:
        scores = {"low text": 0.1, "high text": 0.9, "mid text": 0.5}
        return [scores[t] for t in texts]


def test_rerank_scores_all_candidates_before_topn_slice(monkeypatch) -> None:
    from ai_worker.interfaces.api import main as api_main

    monkeypatch.setattr(api_main, "get_bge_reranker", lambda: _ScriptedRerankRuntime())
    client = TestClient(api_main.create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "ordering",
        "candidates": [
            {"chunkId": "chunk_01", "sourceType": "DOCUMENT", "text": "low text", "rrfScore": 0.9},
            {"chunkId": "chunk_02", "sourceType": "PRIMARY_TRANSCRIPT", "text": "high text", "rrfScore": 0.7},
            {"chunkId": "chunk_03", "sourceType": "AI_SUMMARY", "text": "mid text", "rrfScore": 0.5},
        ],
        "topN": 2,
        "modelVersion": "test-v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)

    assert response.status_code == 200
    items = response.json()["data"]["items"]
    # chunk_03 (0.5) must beat chunk_01 (0.1) — pre-truncation would have
    # dropped chunk_03 entirely.
    assert [(i["chunkId"], i["rank"]) for i in items] == [("chunk_02", 1), ("chunk_03", 2)]
```

- [x] **Step 2: Run, expect failure**

```bash
uv run pytest tests/test_rerank.py::test_rerank_scores_all_candidates_before_topn_slice -v
```

Expected: items are `[("chunk_02", 1), ("chunk_01", 2)]` because chunk_03 was truncated before scoring.

- [x] **Step 3: Implement.** In `ai_worker/interfaces/api/main.py`, replace lines 918-943 (`truncated = …` through the `ranked = […]` block):

```python
        candidates = list(rerank_req.candidates)
        try:
            scores = await runtime.arank(rerank_req.query, [c.text for c in candidates])
        except BgeRerankerRuntimeError as exc:
            return _error_response(
                status_code=503,
                code="RERANK_UNAVAILABLE",
                message=f"rerank inference failed: {exc}",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        # D6: score ALL candidates (request cap is 50), sort by score desc
        # with input-index tie-break, then slice topN. Truncating first
        # silently dropped better candidates outside the head of the RRF
        # ordering.
        indexed = list(enumerate(zip(candidates, scores)))
        indexed.sort(key=lambda item: (-item[1][1], item[0]))
        top = indexed[: rerank_req.topN]
        ranked = [
            RerankResultItem(
                chunkId=cand.chunkId,
                rank=rank + 1,
                rerankScore=round(float(score), 4),
            )
            for rank, (_, (cand, score)) in enumerate(top)
        ]
```

- [x] **Step 4: Run the full rerank suite, expect pass**

```bash
uv run pytest tests/test_rerank.py -v
```

- [x] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/interfaces/api/main.py apps/ai-worker/tests/test_rerank.py
git commit -m "fix(worker): rerank scores all candidates before topN slice (I5/D6)"
```

---

## Task 10 (I6, D8): Inbound HMAC must verify URL_PATH_WITH_QUERY

All four protected endpoints (`/internal/models` GET at main.py:674, `/internal/models/warmup` POST at 712, `/internal/embed` POST at 788, `/internal/rerank` POST at 874) verify `str(request.url.path)` only — `POST /internal/models/warmup?capabilities=all` reads `capabilities` from an unauthenticated part of the request. Contract: the signing string's path component is `URL_PATH_WITH_QUERY` (raw path + `?query` when a query string is present).

**Files:**
- Modify: `ai_worker/interfaces/api/main.py`
- Create: `tests/test_hmac_path_query.py`

- [x] **Step 1: Write the failing test.** Create `tests/test_hmac_path_query.py`:

```python
import hashlib
import hmac
import secrets
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from ai_worker.common.config import settings
from ai_worker.interfaces.api.main import create_app


def _sign(method: str, path_with_query: str, body: bytes, timestamp: str, nonce: str) -> str:
    signing_string = (
        f"{timestamp}\n{nonce}\n{method}\n{path_with_query}\n{hashlib.sha256(body).hexdigest()}"
    )
    sig = hmac.new(
        settings.internal_api_hmac_secret.encode(), signing_string.encode(), hashlib.sha256
    ).hexdigest()
    return f"hmac-sha256={sig}"


def _headers(method: str, signed_path: str, body: bytes) -> dict[str, str]:
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    nonce = f"pathquery_{secrets.token_hex(8)}"
    return {
        "X-Request-Id": "req_pq",
        "X-Trace-Id": "trace_pq",
        "X-Tenant-Id": "tenant_01",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": _sign(method, signed_path, body, timestamp, nonce),
    }


def test_warmup_accepts_signature_over_path_with_query() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/internal/models/warmup?capabilities=embedding",
        content=b"",
        headers=_headers("POST", "/internal/models/warmup?capabilities=embedding", b""),
    )
    assert response.status_code == 200


def test_warmup_rejects_signature_that_omits_the_query_string() -> None:
    client = TestClient(create_app())
    # Signature computed over the bare path — a forged/stripped query must fail.
    response = client.post(
        "/internal/models/warmup?capabilities=embedding",
        content=b"",
        headers=_headers("POST", "/internal/models/warmup", b""),
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "MODELS_AUTH_FAILED"
```

- [x] **Step 2: Run, expect failure**

```bash
uv run pytest tests/test_hmac_path_query.py -v
```

Expected: `test_warmup_accepts_signature_over_path_with_query` fails (server verified bare path, signature mismatch → 401) — and the second test "passes" for the wrong reason until the fix flips the verified string; both must pass after Step 3 for the right reason.

- [x] **Step 3: Implement.** In `ai_worker/interfaces/api/main.py`, add a helper near `_error_response`:

```python
def _signed_path_with_query(request: Request) -> str:
    """URL_PATH_WITH_QUERY per the internal-API HMAC contract: the raw path
    plus '?query' when a query string is present."""
    path = request.url.path
    query = request.url.query
    return f"{path}?{query}" if query else path
```

Then replace `path=str(request.url.path)` with `path=_signed_path_with_query(request)` in all four `verify_hmac_signature(...)` calls (`models`, `warmup`, `embed`, `rerank`).

- [x] **Step 4: Run all HTTP-surface tests, expect pass** (existing tests sign query-less paths, which are unaffected)

```bash
uv run pytest tests/test_hmac_path_query.py tests/test_rerank.py tests/test_embed_endpoint.py tests/test_models_endpoint.py -v
```

- [x] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/interfaces/api/main.py apps/ai-worker/tests/test_hmac_path_query.py
git commit -m "fix(worker): verify HMAC over URL_PATH_WITH_QUERY on all protected endpoints (I6/D8)"
```

---

## Task 11 (I7, D7): Fail closed on default/dev secrets outside dev

`config.py` has no environment field; dev defaults exist for `callback_hmac_secret` ("dev-secret"), `internal_api_hmac_secret` ("dev-internal-secret"), and `admin_jwt_secret` ("dev-admin-secret-32-bytes-fixedXX"). D7: add `AI_WORKER_ENV` (default `dev`); one guard function hard-fails at startup from both `create_app()` and the consumer entrypoint when any of the three secrets equals its dev default AND the environment is not dev; `/internal/ready` also reports/fails. Also remove the dead `ensure_admin_config` (router.py:20-32).

**Files:**
- Modify: `ai_worker/common/config.py`
- Create: `ai_worker/common/secret_guard.py`
- Modify: `ai_worker/interfaces/api/main.py`
- Modify: `ai_worker/interfaces/workers/rabbitmq.py`
- Modify: `ai_worker/admin/router.py`
- Create: `tests/test_secret_guard.py`

- [x] **Step 1: Write the failing tests.** Create `tests/test_secret_guard.py`:

```python
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ai_worker.common.config import settings
from ai_worker.common.secret_guard import assert_secrets_configured, insecure_default_secrets
from ai_worker.interfaces.api.main import create_app


def test_dev_environment_allows_default_secrets() -> None:
    assert settings.env == "dev"  # conftest / default
    assert insecure_default_secrets() == []
    assert_secrets_configured()  # must not raise


def test_non_dev_environment_with_default_secrets_lists_all_offenders(monkeypatch) -> None:
    monkeypatch.setattr(settings, "env", "prod")
    offenders = insecure_default_secrets()
    assert set(offenders) == {
        "AI_WORKER_CALLBACK_HMAC_SECRET",
        "AI_WORKER_INTERNAL_API_HMAC_SECRET",
        "AI_WORKER_ADMIN_JWT_SECRET",
    }
    with pytest.raises(RuntimeError) as exc_info:
        assert_secrets_configured()
    assert "AI_WORKER_CALLBACK_HMAC_SECRET" in str(exc_info.value)


def test_non_dev_environment_with_real_secrets_passes(monkeypatch) -> None:
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "callback_hmac_secret", "x" * 32)
    monkeypatch.setattr(settings, "internal_api_hmac_secret", "y" * 32)
    monkeypatch.setattr(settings, "admin_jwt_secret", "z" * 32)
    assert insecure_default_secrets() == []
    assert_secrets_configured()


def test_create_app_fails_closed_in_prod_with_default_secrets(monkeypatch) -> None:
    monkeypatch.setattr(settings, "env", "prod")
    with pytest.raises(RuntimeError):
        create_app()


def test_ready_endpoint_fails_when_default_secrets_in_prod(monkeypatch) -> None:
    client = TestClient(create_app())  # built under dev
    monkeypatch.setattr(settings, "env", "prod")

    response = client.get("/internal/ready")

    assert response.status_code == 503
    body = response.json()
    assert body["ready"] is False
    assert "AI_WORKER_CALLBACK_HMAC_SECRET" in body["insecureSecrets"]
```

- [x] **Step 2: Run, expect failure**

```bash
uv run pytest tests/test_secret_guard.py -v
```

Expected: `ModuleNotFoundError: No module named 'ai_worker.common.secret_guard'`.

- [x] **Step 3: Implement.**

(a) `ai_worker/common/config.py` — add as the first field of `Settings`:

```python
class Settings(BaseSettings):
    # Deployment environment: "dev" (default), "test", "ci", "staging", "prod".
    # Non-dev environments fail closed on default secrets (secret_guard.py).
    env: str = "dev"
    worker_id: str = "worker_dev_001"
    # … rest unchanged …
```

(b) Create `ai_worker/common/secret_guard.py`:

```python
"""D7 — fail closed when dev-default secrets reach a non-dev environment.

Called from interfaces/api/main.create_app() and the RabbitMQ consumer
entrypoint (interfaces/workers/rabbitmq.run). /internal/ready also surfaces
the offending env var names so a misconfigured pod never goes Ready.
"""

from __future__ import annotations

from ai_worker.common.config import Settings, settings

_DEV_SECRET_DEFAULTS: dict[str, tuple[str, str]] = {
    # field name -> (env var, dev default value)
    "callback_hmac_secret": ("AI_WORKER_CALLBACK_HMAC_SECRET", "dev-secret"),
    "internal_api_hmac_secret": ("AI_WORKER_INTERNAL_API_HMAC_SECRET", "dev-internal-secret"),
    "admin_jwt_secret": ("AI_WORKER_ADMIN_JWT_SECRET", "dev-admin-secret-32-bytes-fixedXX"),
}

_DEV_ENVIRONMENTS = frozenset({"dev", "development", "local", "test", "ci"})


def is_dev_environment(cfg: Settings | None = None) -> bool:
    cfg = cfg or settings
    return cfg.env.strip().lower() in _DEV_ENVIRONMENTS


def insecure_default_secrets(cfg: Settings | None = None) -> list[str]:
    """Env var names whose values still equal their dev defaults — empty in dev."""
    cfg = cfg or settings
    if is_dev_environment(cfg):
        return []
    return sorted(
        env_name
        for field, (env_name, default) in _DEV_SECRET_DEFAULTS.items()
        if getattr(cfg, field) == default
    )


def assert_secrets_configured(cfg: Settings | None = None) -> None:
    cfg = cfg or settings
    offenders = insecure_default_secrets(cfg)
    if offenders:
        raise RuntimeError(
            "refusing to start: dev-default secrets in non-dev environment "
            f"(AI_WORKER_ENV={cfg.env!r}): {', '.join(offenders)}"
        )
```

(c) `ai_worker/interfaces/api/main.py` — add to the imports `from ai_worker.common.secret_guard import assert_secrets_configured, insecure_default_secrets`, make the guard the first line of `create_app()`:

```python
def create_app() -> FastAPI:
    assert_secrets_configured()
    app = FastAPI(title="ai-worker", version="0.1.0", lifespan=lifespan)
    # …
```

and extend `ready()`:

```python
        models = _all_model_infos()
        failed = [m for m in models if m["status"] == "ERROR"]
        insecure = insecure_default_secrets()
        ok = not failed and not insecure
        body = {
            "ready": ok,
            "insecureSecrets": insecure,
            "models": [
                {
                    "name": m["name"],
                    "status": m["status"],
                    "lastError": m["lastError"],
                }
                for m in models
            ],
        }
        return JSONResponse(status_code=200 if ok else 503, content=body)
```

(d) `ai_worker/interfaces/workers/rabbitmq.py`:

```python
from __future__ import annotations

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.secret_guard import assert_secrets_configured
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime


def run() -> None:
    assert_secrets_configured()
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    RabbitMqTaskConsumer(runtime).start_consuming()
```

(e) `ai_worker/admin/router.py` — delete the dead `ensure_admin_config()` function and the `AdminStartupConfigError` class (zero call sites; superseded by the secret guard).

- [x] **Step 4: Run, expect pass; ensure existing app tests still build the app under dev**

```bash
uv run pytest tests/test_secret_guard.py tests/test_health.py tests/test_lifespan.py -v
```

- [x] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/common/config.py apps/ai-worker/ai_worker/common/secret_guard.py apps/ai-worker/ai_worker/interfaces/api/main.py apps/ai-worker/ai_worker/interfaces/workers/rabbitmq.py apps/ai-worker/ai_worker/admin/router.py apps/ai-worker/tests/test_secret_guard.py
git commit -m "fix(worker): fail closed on dev-default secrets outside dev, incl. /internal/ready (I7/D7)"
```

---

## Task 12 (I11, I12): Admin BFF upstream-error envelopes, JSON-body guard, async retry sleep

Three compact fixes: (1) `JavaPublicClient.request` (java_client.py:60-84) lets `httpx.RequestError`/timeouts propagate as bare 500s → wrap into a typed error handled as a 502 envelope `{success:false, error:{code:"UPSTREAM_UNAVAILABLE",…}}` (pattern copied from main.py:349-357); (2) 17 unguarded `await request.json()` sites across `admin/meetings.py` (12), `admin/files.py` (3), `admin/persons.py` (1), `admin/enrollment.py` (2, conditional) → 400 envelope on malformed JSON; (3) `reference_client.py:133` blocks the event loop with `time.sleep(wait)` → `await asyncio.sleep(wait)`.

**Files:**
- Modify: `ai_worker/admin/java_client.py`
- Modify: `ai_worker/admin/envelopes.py`
- Modify: `ai_worker/admin/router.py`
- Modify: `ai_worker/admin/meetings.py`, `ai_worker/admin/persons.py`, `ai_worker/admin/files.py`, `ai_worker/admin/enrollment.py`
- Modify: `ai_worker/interfaces/api/main.py` (register handlers when mounting the admin router)
- Modify: `ai_worker/infrastructure/speaker/reference_client.py`
- Create: `tests/admin/test_upstream_errors.py`
- Create: `tests/test_speaker_reference_client_retry.py`

- [x] **Step 1: Implement the upstream error type.** In `ai_worker/admin/java_client.py`:

```python
class UpstreamUnavailableError(RuntimeError):
    """meeting-api is unreachable / timed out — rendered as a 502 envelope."""
```

and wrap the transport call inside `request(...)`:

```python
        headers = self._headers(claims, request_id, trace_id, idempotency_key)
        if extra_headers:
            headers.update(dict(extra_headers))
        try:
            return await self._client.request(
                method,
                path,
                headers=headers,
                json=json,
                params=params,
                content=content,
            )
        except httpx.RequestError as exc:
            raise UpstreamUnavailableError(f"meeting-api unavailable: {exc}") from exc
```

- [x] **Step 2: Implement the JSON-body guard.** In `ai_worker/admin/envelopes.py`, add:

```python
import json as _json

from starlette.requests import Request


class MalformedJsonBodyError(ValueError):
    """Request body is missing or not valid JSON — rendered as a 400 envelope."""


_NO_DEFAULT = object()


async def parse_json_body(request: Request, default: object = _NO_DEFAULT) -> object:
    """Read and parse the JSON body. Empty body returns ``default`` when given,
    otherwise raises MalformedJsonBodyError; invalid JSON always raises."""
    body = await request.body()
    if not body:
        if default is not _NO_DEFAULT:
            return default
        raise MalformedJsonBodyError("request body must be JSON")
    try:
        return _json.loads(body)
    except Exception as exc:
        raise MalformedJsonBodyError("request body must be valid JSON") from exc
```

- [x] **Step 3: Register exception handlers.** In `ai_worker/admin/router.py`, add:

```python
from fastapi import FastAPI, Request

from ai_worker.admin.envelopes import MalformedJsonBodyError, error
from ai_worker.admin.java_client import JavaPublicClient, UpstreamUnavailableError


def register_admin_exception_handlers(app: FastAPI) -> None:
    """502/400 envelopes for BFF failure modes (I11). Must be attached to the
    FastAPI app (handlers cannot live on an APIRouter)."""

    @app.exception_handler(UpstreamUnavailableError)
    async def _upstream_unavailable(request: Request, exc: UpstreamUnavailableError):
        return error(
            status_code=502,
            code="UPSTREAM_UNAVAILABLE",
            message=str(exc),
            retryable=True,
            request_id=request.headers.get("X-Request-Id"),
            trace_id=request.headers.get("X-Trace-Id"),
        )

    @app.exception_handler(MalformedJsonBodyError)
    async def _malformed_json(request: Request, exc: MalformedJsonBodyError):
        return error(
            status_code=400,
            code="VALIDATION_FAILED",
            message=str(exc),
            retryable=False,
            request_id=request.headers.get("X-Request-Id"),
            trace_id=request.headers.get("X-Trace-Id"),
        )
```

and in `ai_worker/interfaces/api/main.py::_mount_admin_router`, after `app.include_router(build_admin_router())`:

```python
    from ai_worker.admin.router import register_admin_exception_handlers

    register_admin_exception_handlers(app)
```

- [x] **Step 4: Replace the unguarded body parses.** In `ai_worker/admin/meetings.py`, `persons.py`, `files.py`, `enrollment.py`: import the helper (`from ai_worker.admin.envelopes import ok, passthrough, parse_json_body` — adjust per file's existing imports) and replace:

- every `body = await request.json()` with `body = await parse_json_body(request)`
- `body = await request.json() if (await request.body()) else {}` with `body = await parse_json_body(request, default={})`
- `body = await request.json() if (await request.body()) else {"reason": "user_rejected"}` with `body = await parse_json_body(request, default={"reason": "user_rejected"})`
- `body = await request.json() if (await request.body()) else {"format": "DOCX"}` with `body = await parse_json_body(request, default={"format": "DOCX"})`
- in `enrollment.py:371`: `body = await request.json() if raw_body else None` with `body = await parse_json_body(request, default=None)` (drop the now-unused `raw_body` read if it has no other use at that site).

Verify with `grep -n "await request.json()" ai_worker/admin/` → zero remaining hits.

- [x] **Step 5: Fix the event-loop block.** In `ai_worker/infrastructure/speaker/reference_client.py`: add `import asyncio` to the imports, and change line 133:

```python
                await asyncio.sleep(wait)
```

(keep `import time` — it is still used for cache expiry timestamps.)

- [x] **Step 6: Write the tests.** Create `tests/admin/test_upstream_errors.py`:

```python
from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from ai_worker.admin.java_client import JavaPublicClient, UpstreamUnavailableError
from ai_worker.admin.router import build_admin_router, register_admin_exception_handlers
from ._jwt_helpers import make_admin_token


class _DownJavaClient(JavaPublicClient):
    def __init__(self) -> None:
        self._base_url = "http://meeting-api.test"
        self._timeout = 5.0

    async def request(self, method, path, **kwargs):  # type: ignore[override]
        raise UpstreamUnavailableError("meeting-api unavailable: connection refused")


def _app(java_client: JavaPublicClient) -> FastAPI:
    app = FastAPI()
    app.include_router(build_admin_router(java_client=java_client))
    register_admin_exception_handlers(app)
    return app


def _auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {make_admin_token()}"}


@pytest.mark.asyncio
async def test_upstream_unreachable_returns_502_envelope() -> None:
    app = _app(_DownJavaClient())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.get("/admin/meetings", headers=_auth_headers())

    assert response.status_code == 502
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "UPSTREAM_UNAVAILABLE"
    assert body["error"]["retryable"] is True


@pytest.mark.asyncio
async def test_malformed_json_body_returns_400_envelope() -> None:
    app = _app(_DownJavaClient())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(
            "/admin/meetings",
            headers={**_auth_headers(), "Content-Type": "application/json"},
            content=b"{not json",
        )

    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_FAILED"


@pytest.mark.asyncio
async def test_httpx_request_error_is_wrapped_by_java_client(monkeypatch) -> None:
    client = JavaPublicClient(base_url="http://meeting-api.test")

    async def raise_connect_error(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(client._client, "request", raise_connect_error)
    from ai_worker.admin.jwt_middleware import AdminClaims

    claims = AdminClaims(subject="u1", tenant_id="t1", roles=("ADMIN",), raw_token="tok")
    with pytest.raises(UpstreamUnavailableError):
        await client.request("GET", "/api/meetings", claims=claims)
```

Note: check `tests/admin/_jwt_helpers.py` for the exact `make_admin_token` signature and the exact `AdminClaims` constructor fields before finalizing — adjust the two call sites to match (they are test-local details, not contract).

Create `tests/test_speaker_reference_client_retry.py`:

```python
from __future__ import annotations

import asyncio

import httpx
import pytest

from ai_worker.infrastructure.speaker.reference_client import JavaSpeakerReferenceClient


@pytest.mark.asyncio
async def test_batch_retries_5xx_with_async_sleep_then_recovers(monkeypatch) -> None:
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        if attempts["n"] < 3:
            return httpx.Response(500)
        return httpx.Response(200, json={
            "success": True,
            "data": {"items": [
                {"personId": "p1", "speakerProfileId": "sp1", "values": [0.1, 0.2]},
            ]},
        })

    sleeps: list[float] = []

    async def fake_sleep(delay: float) -> None:
        sleeps.append(delay)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    client = JavaSpeakerReferenceClient(
        "http://java.test",
        "secret-not-default",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    result = await client.batch("tenant_01", ["p1"])

    assert attempts["n"] == 3
    assert sleeps == [0.2, 0.4]  # exponential backoff awaited, not time.sleep'd
    assert result["p1"].speaker_profile_id == "sp1"
    await client.close()
```

- [x] **Step 7: Run, expect pass (and no admin regressions)**

```bash
uv run pytest tests/admin/ tests/test_speaker_reference_client_retry.py -v
```

- [x] **Step 8: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/ apps/ai-worker/ai_worker/interfaces/api/main.py apps/ai-worker/ai_worker/infrastructure/speaker/reference_client.py apps/ai-worker/tests/admin/test_upstream_errors.py apps/ai-worker/tests/test_speaker_reference_client_retry.py
git commit -m "fix(worker): 502/400 envelopes for admin BFF upstream/body errors; async retry sleep (I11/I12)"
```

---

## Task 13 (MANDATORY): End-to-end test — exact Java-shaped 8-step message through the production engine

Feed the exact message Java's `ProcessingTaskApplicationService.phase2TaskMessagePayload` produces (8 `MEETING_WORKER_STEPS` incl. ALIGNMENT + RAG_INDEXING; `options.enableAlignment=true`; `inputAudioSha256`/`inputAudioSizeBytes` inside `options`; `channelMap {channelCount:1, layout:"mono"}`; `tos://` audioUri) through `MvpWorkerRuntime` with the production `LocalAudioPipelineEngine` (deterministic/fake model runtimes, real ffprobe preprocessor) and a mocked callback client. Assert: schema-valid, reaches `complete_worker_phase` with 6 completed / 2 skipped and `PARTIAL_SUCCEEDED`, heartbeats observed, and no `/fail`.

**Files:**
- Create: `tests/test_meeting_full_pipeline_e2e.py`

- [x] **Step 1: Create the test.**

```python
"""E2E (review P1 final gate): the EXACT Java-produced 8-step
MEETING_FULL_PIPELINE message must run to completion through MvpWorkerRuntime
with the production LocalAudioPipelineEngine (fake model runtimes are fine).

Message shape mirrors meeting-api
ProcessingTaskApplicationService.phase2TaskMessagePayload + MEETING_WORKER_STEPS
and validates against
packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json.
"""

from __future__ import annotations

import asyncio
import shutil
import wave
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.audio_pipeline import LocalAudioPipelineEngine
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.infrastructure.artifact_store import LocalArtifactStore
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.task_validator import validate_task_message
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.pipeline.asr.runtime import DeterministicAsrRuntime

# Mirrors ProcessingTaskApplicationService.MEETING_WORKER_STEPS (Java).
JAVA_MEETING_WORKER_STEPS = [
    "AUDIO_PREPROCESS",
    "ASR",
    "ALIGNMENT",
    "DIARIZATION",
    "SPEAKER_EMBEDDING",
    "SPEAKER_MATCHING",
    "TRANSCRIPT_MERGE",
    "RAG_INDEXING",
]

IMPLEMENTED_STEPS = [
    "AUDIO_PREPROCESS",
    "ASR",
    "DIARIZATION",
    "SPEAKER_EMBEDDING",
    "SPEAKER_MATCHING",
    "TRANSCRIPT_MERGE",
]


def _java_task_message(audio_uri: str) -> dict:
    # Field-for-field mirror of phase2TaskMessagePayload (meeting-api):
    # pipelineSteps = MEETING_WORKER_STEPS, options carries enableAlignment=true
    # plus inputAudioSha256/inputAudioSizeBytes, channelMap is mono/1.
    return {
        "taskId": "task_e2e_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_e2e",
        "meetingId": "mtg_e2e",
        "attemptNo": 1,
        "pipelineSteps": list(JAVA_MEETING_WORKER_STEPS),
        "expectedInputVersion": {
            "chunkStrategyVersion": "default-zh-v1",
            "transcriptVersion": 0,
        },
        "language": "zh",
        "channelMap": {"channelCount": 1, "layout": "mono"},
        "knownParticipants": [],
        "minSpeakers": 1,
        "maxSpeakers": 4,
        "audioFileId": "file_e2e_01",
        "audioUri": audio_uri,
        "options": {
            "enableAsr": True,
            "enableDiarization": True,
            "enableSpeakerRecognition": True,
            "enableRagIndexing": True,
            "enableAlignment": True,
            "inputAudioSha256": "a" * 64,
            "inputAudioSizeBytes": 32044,
        },
        "traceId": "trace_e2e_01",
        "glossaryTerms": ["声纹", "纪要"],
    }


class SlowDeterministicAsrRuntime(DeterministicAsrRuntime):
    """Same deterministic output, but slow enough to observe heartbeats."""

    async def transcribe(self, audio_path, metadata, language):
        await asyncio.sleep(0.12)
        return await super().transcribe(audio_path, metadata, language)


def _write_wav(path: Path, sample_rate: int = 16000, seconds: float = 0.2) -> None:
    frames = int(sample_rate * seconds)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00\x00" * frames)


@pytest.fixture
def callback_client() -> AsyncMock:
    client = AsyncMock()
    ok = CallbackResponse(http_status=200, accepted=True)
    client.update_step.return_value = ok
    client.submit_artifacts.return_value = ok
    client.submit_transcript.return_value = ok
    client.submit_speaker_candidates.return_value = ok
    client.complete_worker_phase.return_value = ok
    client.fail_task.return_value = ok
    return client


@pytest.mark.asyncio
async def test_java_shaped_full_pipeline_message_completes_with_degraded_steps(
    tmp_path: Path, callback_client: AsyncMock
) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe is required for the production preprocessor")

    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "tenant_e2e" / "mtg_e2e" / "raw.wav"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path)
    audio_path.with_suffix(audio_path.suffix + ".txt").write_text("端到端转录文本", encoding="utf-8")
    audio_uri = "tos://meeting-audio-auska/tenant_e2e/mtg_e2e/raw.wav"

    message = _java_task_message(audio_uri)
    schema_result = validate_task_message(message)
    assert schema_result.valid, schema_result.errors

    state_store = InMemoryWorkflowStateStore()
    engine = LocalAudioPipelineEngine(
        state_store,
        artifact_store=LocalArtifactStore(audio_root),
        asr_runtime=SlowDeterministicAsrRuntime(),
    )
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )

    task = await runtime.consume_message(message)

    assert task is not None

    # 1. No /fail of any kind.
    callback_client.fail_task.assert_not_awaited()

    # 2. Worker phase completed with the expected completed/skipped split.
    callback_client.complete_worker_phase.assert_awaited_once()
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["status"] == "PARTIAL_SUCCEEDED"
    assert complete_kwargs["completed_steps"] == IMPLEMENTED_STEPS
    assert {s["stepName"] for s in complete_kwargs["skipped_steps"]} == {"ALIGNMENT", "RAG_INDEXING"}

    # 3. Skipped steps never received step callbacks.
    step_callback_names = {c.kwargs["step_name"] for c in callback_client.update_step.await_args_list}
    assert "ALIGNMENT" not in step_callback_names
    assert "RAG_INDEXING" not in step_callback_names

    # 4. Heartbeats were observed during the slow ASR step.
    heartbeats = [
        c for c in callback_client.update_step.await_args_list
        if c.kwargs["status"] == "RUNNING" and c.kwargs["progress"] >= 1
    ]
    assert len(heartbeats) >= 1
    assert all(c.kwargs["step_name"] == "ASR" for c in heartbeats)

    # 5. Transcript + artifacts callbacks carry the real manifest id.
    callback_client.submit_transcript.assert_awaited_once()
    transcript_kwargs = callback_client.submit_transcript.await_args.kwargs
    assert transcript_kwargs["transcript_version"] == 1
    assert transcript_kwargs["artifact_manifest_id"] == "artifact_manifest_task_e2e_01_1"
    assert transcript_kwargs["segments"][0]["text"] == "端到端转录文本"

    callback_client.submit_artifacts.assert_awaited_once()
    artifact_types = {
        a["artifactType"]
        for a in callback_client.submit_artifacts.await_args.kwargs["artifacts"]
    }
    assert {"QUALITY_REPORT", "ASR_RAW", "DIARIZATION_TURNS", "TRANSCRIPT_MERGE", "ARTIFACT_MANIFEST"} <= artifact_types

    # 6. Local workflow state mirrors the terminal status.
    snapshot = state_store.get("task_e2e_01")
    assert snapshot is not None
    assert snapshot.status == "PARTIAL_SUCCEEDED"
```

- [x] **Step 2: Run, expect pass**

```bash
uv run pytest tests/test_meeting_full_pipeline_e2e.py -v
```

If any assertion fails, the corresponding upstream task (3, 4, 5, or 7) is incomplete — fix there, not in the test.

- [x] **Step 3: Commit**

```bash
git add apps/ai-worker/tests/test_meeting_full_pipeline_e2e.py
git commit -m "test(worker): e2e Java-shaped 8-step MEETING_FULL_PIPELINE through production engine"
```

---

## Task 14: Full verification sweep

- [x] **Step 1: Full worker test suite**

```bash
cd apps/ai-worker
uv run pytest tests/ -q
```

Expected: all green.

- [x] **Step 2: Type check (CI gate)**

```bash
uv run pyright ai_worker/
```

Expected: 0 errors.

- [x] **Step 3: Contracts gate + codegen drift**

```bash
cd ../../packages/meeting-contracts
npm run check
npm run codegen
git status --short   # must be clean
```

- [x] **Step 4: Import smoke (mirrors CI job 3)**

```bash
cd ../../apps/ai-worker
uv run python -c "import ai_worker.interfaces.api.main, ai_worker.interfaces.workers.rabbitmq; print('ok')"
```

- [x] **Step 5: Final commit if anything was regenerated**

```bash
git add -A
git commit -m "fix(worker): review P1 remediation — verification sweep" --allow-empty
```

---

## Minor findings — triage table (no tasks; fix opportunistically or file follow-ups)

| Location | Problem | One-line fix |
|---|---|---|
| `ai_worker/infrastructure/java_callback/client.py:117-118` | Callback retry budget 3 × 50/100ms is far too thin for a Java restart | Exponential backoff with jitter: base 0.5s, ×4 per attempt, cap 30s, ±20% jitter, 5 attempts |
| `ai_worker/interfaces/api/main.py:949-950` | Rerank response echoes the request's `modelVersion` instead of the runtime's | `RerankResponse(modelVersion=runtime.model_version, …)` and update `test_rerank.py` expectation |
| `ai_worker/infrastructure/worker_runtime.py` (`_fail_for_pipeline_result` / `_fail_for_writeback`) | `context.speaker_embeddings` plaintext not zeroized on mid-pipeline failure | Zeroize all `SpeakerEmbedding.values` from the context in both fail paths before returning |
| `ai_worker/infrastructure/worker_runtime.py:420-427` | Enrollment submits/clears only `embeddings[0]`; extras stay plaintext in memory | Submit first, then overwrite `values` of every embedding in the context with 0.0 |
| `ai_worker/admin/session_store.py` (cleanup loop) | `embedding_preview` lives up to 24h TTL and is never zeroized on eviction | Overwrite `embedding_preview` elements with 0.0 in the eviction/cleanup path |
| `ai_worker/infrastructure/worker_runtime.py` (`complete_worker_phase` calls) | Sends `meetingId: ""` for enrollment tasks (contract says string-or-null) | Make `meeting_id` optional in `JavaCallbackClient.complete_worker_phase` and omit the key when falsy |
| `ai_worker/infrastructure/internal_api/auth.py:71-80` | Nonce replay cache is an O(n) deque scan per request | Replace with `dict[str, float]` nonce→expiry, prune expired entries on insert |
| `ai_worker/interfaces/api/main.py:632-649` | `GET /internal/workflows/{task_id}` and `GET /internal/hardware` are unauthenticated while sibling GET `/internal/models` requires HMAC | Add the same HMAC header set + `verify_hmac_signature(_signed_path_with_query(request))` |
| `ai_worker/pipeline/audio/preprocess.py:60` | `normalized_audio_uri = audio_uri` — no normalization happens; field name misleads | Document the no-op (or rename to `effective_audio_uri`) until real loudness/resample normalization lands |
| `ai_worker/admin/jwt_middleware.py:120 vs 185` | Legacy-token role check is case-insensitive while the HS256 path is case-sensitive; 401s emit `{"detail": …}` not the unified envelope | Make both checks case-sensitive exact-match; convert `JwtValidationError` into the `error(...)` envelope via an exception handler |
| `ai_worker/admin/enrollment.py:91` | No size cap on enrollment audio upload (`await request.body()`) | Reject bodies > 20 MiB with a 413 envelope before writing to disk |
| Stack drift vs SPEC §2 (no Dramatiq/Prefect/structlog) | SPEC promised Dramatiq/Prefect actors | Covered by the Task 6 SPEC.md remediation note (D2) — no further action this volume |

---

## Notes for the parallel Java-side P2 plan (cross-workspace coordination)

1. **PARTIAL_SUCCEEDED is now the steady state for MEETING_FULL_PIPELINE**: the worker reports `/complete phase=WORKER_DAG status=PARTIAL_SUCCEEDED` with `skippedSteps=[ALIGNMENT (reason ALIGNMENT_NOT_IMPLEMENTED — Java sends enableAlignment=true), RAG_INDEXING (reason RAG_INDEXING_REQUIRES_JAVA_CHUNKING)]` and **no step callbacks for skipped steps**. Java's `WORKER_PHASE_COMPLETED` listener must proceed to SUMMARY/EXTRACTION on PARTIAL_SUCCEEDED and must not wait for ALIGNMENT/RAG_INDEXING step rows. Recommended Java follow-up: either drop ALIGNMENT/RAG_INDEXING from `MEETING_WORKER_STEPS` (and the validator's exact-match list via the shared schema) or keep dispatching the existing post-transcript `TEXT_EMBEDDING` tasks as the real RAG indexing path and accept the permanent partial.
2. **/artifacts persistence (D9)**: worker now POSTs `artifacts` with `artifactType ∈ {QUALITY_REPORT, ASR_RAW, DIARIZATION_TURNS, TRANSCRIPT_MERGE, ARTIFACT_MANIFEST}`, `artifactUri` (`tos://…`), `sha256`, `sizeBytes`, plus top-level `artifactManifestId = artifact_manifest_{taskId}_{attemptNo}`; `/transcript` carries the same `artifactManifestId`.
3. **Heartbeats (D1)**: every 20s per running step, `status=RUNNING, progress=1` (constant floor), stable idempotency key `{taskId}:{stepName}:heartbeat:{attemptNo}`; Java renews lease +120s and stops pre-claiming at creation — the worker's first callback claims with `X-Lease-Owner {workerId}:{taskId}:{attemptNo}` (unchanged).
4. **New /fail codes Java will receive**: `WORKER_INTERNAL_ERROR` (retryable=true — should re-enqueue under the normal retry budget), `ASR_MODEL_LOAD_FAILED`, `DIARIZATION_GPU_OOM`, `SPEAKER_EMBEDDING_GPU_OOM`, plus the previously-unregistered codes now in `error-codes.yaml`. If `meeting-api-client` mirrors error codes in a hand-written enum, sync it (`npm run check` does not gate this).
5. **OOM**: after an `*_GPU_OOM` `/fail`, the worker process exits (137) right after settling the delivery — expect the lease to be free and the attempt already failed, not orphaned.
