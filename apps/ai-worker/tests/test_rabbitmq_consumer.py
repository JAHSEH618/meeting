from __future__ import annotations

import asyncio
import json
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


class _OpenConsumerChannel:
    is_open = True

    def __init__(self) -> None:
        self.stopped = False

    def stop_consuming(self) -> None:
        self.stopped = True


class _OpenConnection:
    is_open = True

    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def test_on_message_dispatches_json_to_runtime_and_acks() -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "task_01"}).encode())
    consumer.wait_idle()

    runtime.consume_message.assert_awaited_once_with({"taskId": "task_01"})
    assert channel.acked == ["delivery_01"]
    assert channel.rejected == []
    consumer.stop()


def test_on_message_rejects_when_task_raises() -> None:
    # A failure that escapes consume_message must reject (not ack) so the task
    # is not silently lost.
    runtime = AsyncMock()
    runtime.consume_message.side_effect = RuntimeError("boom")
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "task_01"}).encode())
    consumer.wait_idle()

    assert channel.acked == []
    assert channel.rejected == [("delivery_01", False)]
    consumer.stop()


def test_on_message_rejects_invalid_json_without_requeue() -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, b"{bad")

    runtime.consume_message.assert_not_called()
    assert channel.acked == []
    assert channel.rejected == [("delivery_01", False)]


def test_on_message_reuses_one_event_loop_across_messages() -> None:
    # Regression for the per-message asyncio.run(): all messages must run on a
    # single long-lived loop so module-level asyncio primitives (inference
    # semaphores, pooled clients) stay bound to one loop.
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "t1"}).encode())
    loop_after_first = consumer._loop
    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "t2"}).encode())
    consumer.wait_idle()

    assert loop_after_first is not None
    assert consumer._loop is loop_after_first
    assert not loop_after_first.is_closed()

    consumer.stop()
    assert loop_after_first.is_closed()


def test_stop_drains_pending_background_tasks() -> None:
    # A fire-and-forget background task created during message handling (e.g. a
    # TOS artifact backup) must be drained on stop, not silently destroyed.
    completed = {"done": False}

    async def slow_background() -> None:
        await asyncio.sleep(0)
        completed["done"] = True

    async def consume(_msg: dict) -> None:
        asyncio.get_running_loop().create_task(slow_background())

    runtime = AsyncMock()
    runtime.consume_message.side_effect = consume
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "t1"}).encode())
    assert completed["done"] is False  # not awaited inline

    consumer.stop()
    assert completed["done"] is True
    assert channel.acked == ["delivery_01"]


def test_stop_closes_shared_backup_store(monkeypatch) -> None:
    # The lazily created shared TOS backup client must be released on the
    # consumer shutdown path, after in-flight backup tasks have drained.
    import ai_worker.infrastructure.mq.rabbitmq_consumer as consumer_module

    closed = {"count": 0}

    async def fake_aclose_backup_store() -> None:
        closed["count"] += 1

    monkeypatch.setattr(consumer_module, "aclose_backup_store", fake_aclose_backup_store)

    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()
    consumer._on_message(channel, _Method(), None, json.dumps({"taskId": "t1"}).encode())
    consumer.wait_idle()

    consumer.stop()

    assert closed["count"] == 1
    runtime.stop.assert_awaited_once()


def test_stop_closes_runtime_after_rabbitmq_connection() -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _OpenConsumerChannel()
    connection = _OpenConnection()
    consumer._channel = channel
    consumer._connection = connection

    consumer.stop()

    assert channel.stopped is True
    assert connection.closed is True
    runtime.stop.assert_awaited_once()
