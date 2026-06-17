# AI Worker Stage 1 Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing ai-worker RabbitMQ consumer and Java callback path recover from transient infrastructure failures while preserving the current FastAPI health/readiness model.

**Architecture:** Keep the existing FastAPI endpoints in `interfaces/api/main.py` and the existing RabbitMQ consumer in `infrastructure/mq/rabbitmq_consumer.py`. Add configuration, retry parsing, reconnect loops, failure classification, graceful shutdown, and focused tests without introducing new service frameworks or duplicate health routers.

**Tech Stack:** Python 3.11, FastAPI, pika, httpx, prometheus-client, pytest, pyright, Docker/Kubernetes manifests already in the repository.

---

## File Structure

### Modify

- `apps/ai-worker/ai_worker/common/config.py`
  Add stability settings with defaults.

- `apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py`
  Add reconnect loop, `is_connected()`, failure classification, and configurable timeout handling.

- `apps/ai-worker/ai_worker/infrastructure/java_callback/client.py`
  Replace hard-coded short retry with configured retry delays and retryable-status classification.

- `apps/ai-worker/ai_worker/interfaces/workers/rabbitmq.py`
  Add SIGTERM/SIGINT shutdown handling around the existing consumer.

- `apps/ai-worker/ai_worker/interfaces/api/main.py`
  Keep current health endpoints. Add no new health router. Tests may be adjusted only if the response contract changes.

- `apps/ai-worker/ai_worker/observability/gpu_metrics.py`
  May be extended with RabbitMQ/callback counters if no separate metrics module is introduced.

- `apps/ai-worker/README.md`
  Document the new stability settings and current health semantics.

### Create

- `apps/ai-worker/ai_worker/common/retry_config.py`
  Parse comma-separated retry delay settings into validated float seconds.

- `apps/ai-worker/ai_worker/observability/worker_metrics.py`
  Add RabbitMQ and callback counters/gauges. Keep metric labels low-cardinality.

- `apps/ai-worker/tests/test_retry_config.py`

### Extend Existing Tests

- `apps/ai-worker/tests/test_rabbitmq_consumer.py`
  Extend existing file instead of creating a parallel infrastructure test tree.

- `apps/ai-worker/tests/test_callback_client.py`
  Extend existing file.

### Do Not Create

- `apps/ai-worker/ai_worker/interfaces/api/health.py`
  Health/readiness already live in `interfaces/api/main.py`.

- `apps/ai-worker/Dockerfile.optimized`
  Docker changes belong in the existing Dockerfile and are not part of Stage 1.

- `infra/meeting-infra/docker/compose/docker-compose.observability.yml`
  Observability is already a profile in `infra/meeting-infra/docker/compose/docker-compose.yml`.

---

## Task 1: Add Stability Settings and Retry Parsing

**Files:**
- Modify: `apps/ai-worker/ai_worker/common/config.py`
- Create: `apps/ai-worker/ai_worker/common/retry_config.py`
- Create: `apps/ai-worker/tests/test_retry_config.py`

- [ ] **Step 1: Write retry delay parser tests**

Create `apps/ai-worker/tests/test_retry_config.py`:

```python
from __future__ import annotations

import pytest

from ai_worker.common.retry_config import parse_retry_delays


def test_parse_retry_delays_accepts_comma_separated_seconds() -> None:
    assert parse_retry_delays("1, 2.5,5") == (1.0, 2.5, 5.0)


def test_parse_retry_delays_rejects_empty_value() -> None:
    with pytest.raises(ValueError, match="must contain at least one delay"):
        parse_retry_delays("")


def test_parse_retry_delays_rejects_negative_value() -> None:
    with pytest.raises(ValueError, match="must be >= 0"):
        parse_retry_delays("1,-2")
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_retry_config.py -q
```

Expected: fail with `ModuleNotFoundError: No module named 'ai_worker.common.retry_config'`.

- [ ] **Step 3: Add settings**

Append these fields inside `Settings` in `apps/ai-worker/ai_worker/common/config.py`, before `model_config`:

```python
    # Stability controls.
    rabbitmq_reconnect_initial_delay_seconds: float = 1.0
    rabbitmq_reconnect_max_delay_seconds: float = 30.0
    rabbitmq_reconnect_max_attempts: int = 0  # 0 means retry forever.
    rabbitmq_requeue_max_attempts: int = 3
    task_execution_timeout_seconds: float = 30 * 60
    callback_retry_delays: str = "1,2,5,10"
    callback_timeout_seconds: float = 30.0
```

- [ ] **Step 4: Implement the parser**

Create `apps/ai-worker/ai_worker/common/retry_config.py`:

```python
from __future__ import annotations


def parse_retry_delays(value: str) -> tuple[float, ...]:
    delays: list[float] = []
    for raw in value.split(","):
        item = raw.strip()
        if not item:
            continue
        delay = float(item)
        if delay < 0:
            raise ValueError("retry delay must be >= 0")
        delays.append(delay)
    if not delays:
        raise ValueError("retry delays must contain at least one delay")
    return tuple(delays)
```

- [ ] **Step 5: Verify parser tests pass**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_retry_config.py -q
```

Expected: `3 passed`.

---

## Task 2: Add Worker-Level Metrics

**Files:**
- Create: `apps/ai-worker/ai_worker/observability/worker_metrics.py`
- Create: `apps/ai-worker/tests/test_worker_metrics.py`

- [ ] **Step 1: Write import smoke test**

Create `apps/ai-worker/tests/test_worker_metrics.py`:

```python
from __future__ import annotations


def test_worker_metrics_imports() -> None:
    from ai_worker.observability.worker_metrics import (
        CALLBACK_REQUESTS,
        CALLBACK_RETRIES,
        RABBITMQ_CONNECTED,
        RABBITMQ_RECONNECTS,
    )

    assert CALLBACK_REQUESTS is not None
    assert CALLBACK_RETRIES is not None
    assert RABBITMQ_CONNECTED is not None
    assert RABBITMQ_RECONNECTS is not None
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_worker_metrics.py -q
```

Expected: fail with `ModuleNotFoundError`.

- [ ] **Step 3: Implement metrics**

Create `apps/ai-worker/ai_worker/observability/worker_metrics.py`:

```python
from __future__ import annotations

from prometheus_client import Counter, Gauge

RABBITMQ_RECONNECTS = Counter(
    "ai_worker_rabbitmq_reconnects_total",
    "RabbitMQ consumer reconnect attempts.",
    labelnames=("reason",),
)

RABBITMQ_CONNECTED = Gauge(
    "ai_worker_rabbitmq_connected",
    "Whether the RabbitMQ consumer connection is currently open.",
)

CALLBACK_REQUESTS = Counter(
    "ai_worker_callback_requests_total",
    "Java callback requests by operation, outcome, and stable error code.",
    labelnames=("operation", "outcome", "error_code"),
)

CALLBACK_RETRIES = Counter(
    "ai_worker_callback_retries_total",
    "Java callback retry attempts by operation.",
    labelnames=("operation",),
)
```

- [ ] **Step 4: Verify metric import test passes**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_worker_metrics.py -q
```

Expected: `1 passed`.

---

## Task 3: Make RabbitMQ Consumer Reconnect

**Files:**
- Modify: `apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py`
- Modify: `apps/ai-worker/tests/test_rabbitmq_consumer.py`

- [ ] **Step 1: Add reconnect tests to the existing test file**

Append to `apps/ai-worker/tests/test_rabbitmq_consumer.py`:

```python
from pika.exceptions import AMQPConnectionError


def test_start_consuming_reconnects_after_connection_error(monkeypatch) -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    calls = 0
    sleeps: list[float] = []

    def connect_and_consume() -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise AMQPConnectionError("temporary outage")
        raise KeyboardInterrupt()

    monkeypatch.setattr(consumer, "_connect_and_consume", connect_and_consume)
    monkeypatch.setattr("time.sleep", lambda seconds: sleeps.append(seconds))

    consumer.start_consuming()

    assert calls == 2
    assert sleeps == [1.0]


def test_is_connected_returns_false_before_connection() -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)

    assert consumer.is_connected() is False
```

- [ ] **Step 2: Run tests and confirm reconnect test fails**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_rabbitmq_consumer.py -q
```

Expected: fail because `_connect_and_consume` and `is_connected` do not exist.

- [ ] **Step 3: Refactor consumer**

In `apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py` add imports:

```python
import time

from pika.exceptions import AMQPConnectionError, ConnectionClosedByBroker, StreamLostError

from ai_worker.observability.worker_metrics import RABBITMQ_CONNECTED, RABBITMQ_RECONNECTS
```

Replace `start_consuming()` with:

```python
    def start_consuming(self) -> None:
        attempt = 0
        delay = settings.rabbitmq_reconnect_initial_delay_seconds
        max_attempts = settings.rabbitmq_reconnect_max_attempts
        max_delay = settings.rabbitmq_reconnect_max_delay_seconds

        while True:
            try:
                self._connect_and_consume()
                attempt = 0
                delay = settings.rabbitmq_reconnect_initial_delay_seconds
            except KeyboardInterrupt:
                logger.info("RabbitMQ task consumer stopped by interrupt")
                break
            except (AMQPConnectionError, ConnectionClosedByBroker, StreamLostError) as exc:
                attempt += 1
                RABBITMQ_CONNECTED.set(0)
                RABBITMQ_RECONNECTS.labels(reason=type(exc).__name__).inc()
                if max_attempts > 0 and attempt > max_attempts:
                    logger.exception("RabbitMQ reconnect attempts exhausted")
                    raise
                logger.warning(
                    "RabbitMQ connection lost; reconnecting in %.1fs attempt=%s error=%s",
                    delay,
                    attempt,
                    exc,
                )
                time.sleep(delay)
                delay = min(delay * 2, max_delay)
```

Add `_connect_and_consume()` by moving the old `start_consuming()` body into it, and set the gauge after a successful channel setup:

```python
    def _connect_and_consume(self) -> None:
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
        RABBITMQ_CONNECTED.set(1)
        logger.info("RabbitMQ task consumer started for queues=%s", self.config.queues)
        channel.start_consuming()
```

Add:

```python
    def is_connected(self) -> bool:
        return bool(
            self._connection is not None
            and self._connection.is_open
            and self._channel is not None
            and self._channel.is_open
        )
```

- [ ] **Step 4: Verify RabbitMQ tests pass**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_rabbitmq_consumer.py -q
```

Expected: all tests in the file pass.

---

## Task 4: Make Callback Retry Policy Configurable

**Files:**
- Modify: `apps/ai-worker/ai_worker/infrastructure/java_callback/client.py`
- Modify: `apps/ai-worker/tests/test_callback_client.py`

- [ ] **Step 1: Add retryable-status tests**

Extend `apps/ai-worker/tests/test_callback_client.py` with tests that assert:

```python
def test_callback_client_treats_401_as_non_retryable() -> None:
    client = JavaCallbackClient(base_url="http://meeting-api")
    assert client._is_retryable_status(401) is False


def test_callback_client_treats_409_as_non_retryable() -> None:
    client = JavaCallbackClient(base_url="http://meeting-api")
    assert client._is_retryable_status(409) is False


def test_callback_client_treats_503_and_429_as_retryable() -> None:
    client = JavaCallbackClient(base_url="http://meeting-api")
    assert client._is_retryable_status(503) is True
    assert client._is_retryable_status(429) is True
```

- [ ] **Step 2: Run callback tests and confirm new tests fail**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_callback_client.py -q
```

Expected: fail because `_is_retryable_status` does not exist.

- [ ] **Step 3: Implement retryable classification and configured delays**

In `JavaCallbackClient.__init__`, add:

```python
        from ai_worker.common.retry_config import parse_retry_delays

        self.retry_delays = parse_retry_delays(settings.callback_retry_delays)
        self.timeout_seconds = settings.callback_timeout_seconds
```

Add methods:

```python
    def _is_retryable_status(self, status_code: int) -> bool:
        return status_code == 429 or 500 <= status_code <= 599

    def _operation_for_path(self, path: str) -> str:
        parts = [part for part in path.split("/") if part]
        return parts[-1] if parts else "unknown"
```

Keep the `_request(..., max_retries: int = 3)` parameter for compatibility with existing tests and callers, but stop using it to drive retry count. The configured `settings.callback_retry_delays` is the source of truth.

Update `_request()` loop to use configured delays:

```python
        operation = self._operation_for_path(path)
        attempts = self.retry_delays
        for attempt_index, delay in enumerate(self.retry_delays):
            headers = self._build_headers(
                method, path, body_str, task_id, attempt_no, trace_id, idempotency_key
            )
            try:
                async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                    response = await client.request(method, url, content=body_str, headers=headers)
                    if response.status_code == 409:
                        CALLBACK_REQUESTS.labels(operation=operation, outcome="failed", error_code="CALLBACK_IDEMPOTENCY_CONFLICT").inc()
                        return CallbackResponse(http_status=409, accepted=False, error_code="CALLBACK_IDEMPOTENCY_CONFLICT")
                    if response.status_code == 401:
                        CALLBACK_REQUESTS.labels(operation=operation, outcome="failed", error_code="CALLBACK_AUTH_FAILED").inc()
                        return CallbackResponse(http_status=401, accepted=False, error_code="CALLBACK_AUTH_FAILED")
                    if response.status_code < 400:
                        CALLBACK_REQUESTS.labels(operation=operation, outcome="success", error_code="NONE").inc()
                        try:
                            response_body = response.json()
                        except ValueError:
                            response_body = {}
                        return CallbackResponse(http_status=response.status_code, accepted=True, body=response_body)
                    last_error = f"HTTP {response.status_code}"
                    if not self._is_retryable_status(response.status_code):
                        CALLBACK_REQUESTS.labels(operation=operation, outcome="failed", error_code="CALLBACK_HTTP_4XX").inc()
                        return CallbackResponse(http_status=response.status_code, accepted=False, error_code="CALLBACK_HTTP_4XX", body={"message": last_error})
            except Exception as e:
                last_error = str(e)
            if attempt_index < len(attempts) - 1:
                CALLBACK_RETRIES.labels(operation=operation).inc()
                await asyncio.sleep(delay)

        CALLBACK_REQUESTS.labels(operation=operation, outcome="failed", error_code="WRITEBACK_FAILED").inc()
```

Also import:

```python
from ai_worker.observability.worker_metrics import CALLBACK_REQUESTS, CALLBACK_RETRIES
```

- [ ] **Step 4: Verify callback tests pass**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_callback_client.py -q
```

Expected: all callback tests pass.

---

## Task 5: Add Bounded Requeue for Message Processing Failures

**Files:**
- Modify: `apps/ai-worker/ai_worker/infrastructure/mq/rabbitmq_consumer.py`
- Modify: `apps/ai-worker/tests/test_rabbitmq_consumer.py`

- [ ] **Step 1: Add bounded requeue tests**

Append tests:

```python
from ai_worker.common.config import settings


class _Properties:
    def __init__(self, headers: dict | None = None) -> None:
        self.headers = headers or {}


def test_on_message_rejects_timeout_with_requeue_before_limit() -> None:
    runtime = AsyncMock()
    runtime.consume_message.side_effect = TimeoutError("task timed out")
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(
        channel,
        _Method(),
        _Properties(headers={"x-delivery-count": 1}),
        json.dumps({"taskId": "task_01"}).encode(),
    )

    assert channel.acked == []
    assert channel.rejected == [("delivery_01", True)]


def test_on_message_rejects_timeout_without_requeue_at_limit(monkeypatch) -> None:
    runtime = AsyncMock()
    runtime.consume_message.side_effect = TimeoutError("task timed out")
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    monkeypatch.setattr(settings, "rabbitmq_requeue_max_attempts", 3)

    consumer._on_message(
        channel,
        _Method(),
        _Properties(headers={"x-delivery-count": 3}),
        json.dumps({"taskId": "task_01"}).encode(),
    )

    assert channel.acked == []
    assert channel.rejected == [("delivery_01", False)]
```

- [ ] **Step 2: Run test and confirm it fails**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_rabbitmq_consumer.py -q
```

Expected: timeout currently rejects without requeue and does not inspect delivery count.

- [ ] **Step 3: Add helper methods**

In `RabbitMqTaskConsumer`, add:

```python
    def _delivery_count(self, properties: Any) -> int:
        headers = getattr(properties, "headers", None) or {}
        value = headers.get("x-delivery-count", 0)
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    def _should_requeue_exception(self, exc: BaseException, properties: Any) -> bool:
        if not isinstance(exc, (TimeoutError, AMQPConnectionError, StreamLostError)):
            return False
        max_attempts = settings.rabbitmq_requeue_max_attempts
        if max_attempts <= 0:
            return True
        return self._delivery_count(properties) < max_attempts

    async def _consume_with_timeout(self, raw_message: dict[str, Any]) -> None:
        await asyncio.wait_for(
            self.runtime.consume_message(raw_message),
            timeout=settings.task_execution_timeout_seconds,
        )
```

Update `_on_message()`:

```python
            raw_message = json.loads(body.decode("utf-8"))
            asyncio.run(self._consume_with_timeout(raw_message))
            channel.basic_ack(delivery_tag=method.delivery_tag)
```

Rename `_properties` to `properties` in `_on_message()`, then replace the broad exception block with:

```python
        except Exception as exc:
            requeue = self._should_requeue_exception(exc, properties)
            logger.exception("task message failed; rejecting requeue=%s", requeue)
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=requeue)
```

- [ ] **Step 4: Verify RabbitMQ tests pass**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_rabbitmq_consumer.py -q
```

Expected: all tests pass.

---

## Task 6: Add Graceful Shutdown to Consumer Entrypoint

**Files:**
- Modify: `apps/ai-worker/ai_worker/interfaces/workers/rabbitmq.py`
- Create: `apps/ai-worker/tests/test_rabbitmq_entrypoint.py`

- [ ] **Step 1: Write entrypoint smoke test**

Create `apps/ai-worker/tests/test_rabbitmq_entrypoint.py`:

```python
from __future__ import annotations

from ai_worker.interfaces.workers import rabbitmq


def test_rabbitmq_entrypoint_exposes_run() -> None:
    assert callable(rabbitmq.run)
```

- [ ] **Step 2: Add signal handling**

Update `apps/ai-worker/ai_worker/interfaces/workers/rabbitmq.py`:

```python
from __future__ import annotations

import logging
import signal
import sys

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime

logger = logging.getLogger(__name__)


def run() -> None:
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    consumer = RabbitMqTaskConsumer(runtime)

    def shutdown_handler(signum, _frame) -> None:
        logger.info("stopping RabbitMQ consumer signal=%s", signum)
        try:
            consumer.stop()
        finally:
            sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown_handler)
    signal.signal(signal.SIGINT, shutdown_handler)
    consumer.start_consuming()
```

- [ ] **Step 3: Verify entrypoint smoke test**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_rabbitmq_entrypoint.py -q
```

Expected: `1 passed`.

---

## Task 7: Keep Health Semantics and Add Regression Tests

**Files:**
- Modify: `apps/ai-worker/tests/test_health.py`
- Modify: `apps/ai-worker/README.md`

- [ ] **Step 1: Add regression tests for existing health contract**

Append to `apps/ai-worker/tests/test_health.py`:

```python
def test_health_is_liveness_only() -> None:
    client = TestClient(create_app())

    response = client.get("/internal/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert "dependencies" in response.json()


def test_hardware_endpoint_exists() -> None:
    client = TestClient(create_app())

    response = client.get("/internal/hardware")

    assert response.status_code == 200
    assert "torch" in response.json()
    assert "resolvedDevices" in response.json()
```

- [ ] **Step 2: Run health tests**

Run:

```bash
cd apps/ai-worker
uv run pytest tests/test_health.py -q
```

Expected: all health tests pass.

- [ ] **Step 3: Update README stability section**

Add to `apps/ai-worker/README.md`:

```markdown
## 稳定性与健康检查

- `/internal/health` 是 liveness-only：只表示进程可响应，不检查模型 checksum，也不把 RabbitMQ 短暂断线作为重启条件。
- `/internal/ready` 是 readiness：汇总模型状态和 checksum guard，模型损坏或 real-mode 依赖缺失时返回 503。
- `/internal/hardware` 暴露 torch/CUDA/MPS/package/device 诊断。
- `/metrics` 复用 API 端口 `8090`，Prometheus 不需要单独的 `8091` 端口。

稳定性相关环境变量：

```bash
AI_WORKER_RABBITMQ_RECONNECT_INITIAL_DELAY_SECONDS=1
AI_WORKER_RABBITMQ_RECONNECT_MAX_DELAY_SECONDS=30
AI_WORKER_RABBITMQ_RECONNECT_MAX_ATTEMPTS=0
AI_WORKER_TASK_EXECUTION_TIMEOUT_SECONDS=1800
AI_WORKER_CALLBACK_RETRY_DELAYS=1,2,5,10
AI_WORKER_CALLBACK_TIMEOUT_SECONDS=30
```
```

---

## Task 8: Focused Verification

**Files:**
- None

- [ ] **Step 1: Run focused tests**

Run:

```bash
cd apps/ai-worker
uv run pytest \
  tests/test_retry_config.py \
  tests/test_worker_metrics.py \
  tests/test_rabbitmq_consumer.py \
  tests/test_callback_client.py \
  tests/test_rabbitmq_entrypoint.py \
  tests/test_health.py \
  -q
```

Expected: selected tests pass.

- [ ] **Step 2: Run type check**

Run:

```bash
cd apps/ai-worker
uv run pyright ai_worker/
```

Expected: pyright reports no errors for touched modules.

- [ ] **Step 3: Run import smoke test**

Run:

```bash
cd apps/ai-worker
uv run python -c "import ai_worker; from ai_worker.common.config import settings; print(settings.worker_id)"
```

Expected: prints the configured worker id, default `worker_dev_001` in dev.

---

## Self-Review Checklist

- [x] No plan step creates `interfaces/api/health.py`.
- [x] No plan introduces a separate `8091` metrics port.
- [x] No plan introduces `Dockerfile.optimized`.
- [x] Stage 1 focuses on RabbitMQ, callback retry, shutdown, health contract tests, and docs.
- [x] Existing test files are extended where they already exist.
- [x] Health semantics match current `interfaces/api/main.py`.

## Execution Handoff

Plan updated and saved to `docs/superpowers/plans/2026-06-16-ai-worker-stage1-stability.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - use `superpowers:subagent-driven-development`, one fresh worker per task, review between tasks.
2. **Inline Execution** - use `superpowers:executing-plans`, execute tasks in this session with checkpoints.
