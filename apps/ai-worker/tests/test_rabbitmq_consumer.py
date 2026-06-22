from __future__ import annotations

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

    runtime.consume_message.assert_awaited_once_with({"taskId": "task_01"})
    assert channel.acked == ["delivery_01"]
    assert channel.rejected == []


def test_on_message_rejects_invalid_json_without_requeue() -> None:
    runtime = AsyncMock()
    consumer = RabbitMqTaskConsumer(runtime)
    channel = _Channel()

    consumer._on_message(channel, _Method(), None, b"{bad")

    runtime.consume_message.assert_not_called()
    assert channel.acked == []
    assert channel.rejected == [("delivery_01", False)]


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
