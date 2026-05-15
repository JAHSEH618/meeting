"""Pluggable model runtime implementations.

Each subpackage hosts a single capability (embedding, rerank, asr, etc).
Runtimes share a common shape:
  - `model_version: str`
  - `status: ModelStatus`
  - `last_error: str | None`
  - `device: str`
  - `async ensure_loaded() -> None`
  - capability-specific inference method(s)

Real (GPU / CPU PyTorch) and fake (deterministic) variants live alongside
each other so the FastAPI app, MQ consumer, and tests can swap modes via
the `AI_WORKER_USE_FAKE_RUNTIME` setting without rewriting wiring.
"""
