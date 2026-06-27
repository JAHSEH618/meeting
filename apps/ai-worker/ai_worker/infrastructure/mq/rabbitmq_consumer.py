from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from typing import Any

import pika

from ai_worker.common.config import settings
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime

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
    def __init__(
        self,
        runtime: MvpWorkerRuntime,
        config: RabbitMqConsumerConfig | None = None,
    ) -> None:
        self.runtime = runtime
        self.config = config or RabbitMqConsumerConfig()
        self._connection: pika.BlockingConnection | None = None
        self._channel: Any | None = None
        self._loop: asyncio.AbstractEventLoop | None = None

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

    def _ensure_loop(self) -> asyncio.AbstractEventLoop:
        # One long-lived event loop for the consumer's lifetime. asyncio.run()
        # created and closed a fresh loop per message, which left module-level
        # asyncio primitives bound to a dead loop — the per-device inference
        # semaphores (model_runtime.concurrency) and any pooled httpx client
        # would then raise "bound to a different event loop" on the next task.
        # Reusing one loop keeps them valid and lets fire-and-forget background
        # tasks (e.g. TOS artifact backups) survive between messages.
        if self._loop is None or self._loop.is_closed():
            self._loop = asyncio.new_event_loop()
        return self._loop

    def stop(self) -> None:
        if self._channel and self._channel.is_open:
            self._channel.stop_consuming()
        if self._connection and self._connection.is_open:
            self._connection.close()
        loop = self._loop
        if loop is not None and not loop.is_closed():
            loop.run_until_complete(self.runtime.stop())
            # Drain fire-and-forget background tasks (e.g. TOS backups) before
            # tearing the loop down so they are not silently destroyed.
            pending = asyncio.all_tasks(loop)
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()
            self._loop = None
        else:
            asyncio.run(self.runtime.stop())

    def _on_message(self, channel: Any, method: Any, _properties: Any, body: bytes) -> None:
        try:
            raw_message = json.loads(body.decode("utf-8"))
            self._ensure_loop().run_until_complete(self.runtime.consume_message(raw_message))
            channel.basic_ack(delivery_tag=method.delivery_tag)
        except json.JSONDecodeError:
            logger.exception("invalid JSON task message; rejecting without requeue")
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
        except Exception:
            logger.exception("task message failed; rejecting without requeue")
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
