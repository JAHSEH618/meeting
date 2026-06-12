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
