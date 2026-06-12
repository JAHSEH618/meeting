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
