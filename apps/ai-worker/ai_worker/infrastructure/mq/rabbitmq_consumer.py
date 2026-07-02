from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from concurrent.futures import Future
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
    prefetch_count: int = settings.rabbitmq_prefetch_count
    queues: tuple[str, ...] = tuple(
        queue.strip()
        for queue in settings.rabbitmq_task_queues.split(",")
        if queue.strip()
    )


class RabbitMqTaskConsumer:
    """Blocking-pika consumer that runs task work off the I/O thread.

    pika's ``BlockingConnection`` cannot send AMQP heartbeats while a message
    callback is blocked. A multi-minute ASR/diarization task therefore used to
    stall the connection until the broker dropped it, and the subsequent
    ``basic_ack`` failed and the message was redelivered forever.

    Here the I/O thread only parses the message and hands the coroutine to a
    long-lived worker event loop running on a dedicated thread. The I/O thread
    returns immediately and keeps the connection alive; when the task finishes,
    the ack/reject is scheduled back onto the I/O thread via
    ``add_callback_threadsafe`` (the only thread-safe way to touch a pika
    channel). A single shared loop keeps module-level asyncio primitives (the
    per-device inference semaphores, pooled httpx clients) valid across
    messages.
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
        self._loop: asyncio.AbstractEventLoop | None = None
        self._loop_thread: threading.Thread | None = None
        self._inflight: set[Future] = set()
        self._inflight_lock = threading.Lock()
        self._warmup_future: Future | None = None

    # ── worker event loop (dedicated thread) ────────────────────────────────

    def _ensure_loop(self) -> asyncio.AbstractEventLoop:
        if self._loop is None or self._loop.is_closed():
            self._loop = asyncio.new_event_loop()
            self._loop_thread = threading.Thread(
                target=self._run_loop,
                name="ai-worker-consumer-loop",
                daemon=True,
            )
            self._loop_thread.start()
        return self._loop

    def _run_loop(self) -> None:
        assert self._loop is not None
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    def wait_idle(self, timeout: float = 30.0) -> None:
        """Block until all in-flight task futures have settled (ack/reject ran).

        Used by tests and graceful shutdown; not on the hot path.
        """
        deadline = time.monotonic() + timeout
        while True:
            with self._inflight_lock:
                if not self._inflight:
                    return
            if time.monotonic() >= deadline:
                raise TimeoutError("in-flight consumer tasks did not settle in time")
            time.sleep(0.005)

    # ── lifecycle ───────────────────────────────────────────────────────────

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
        channel.basic_qos(prefetch_count=self.config.prefetch_count)
        for queue in self.config.queues:
            channel.basic_consume(
                queue=queue,
                on_message_callback=self._on_message,
                auto_ack=False,
            )
        loop = self._ensure_loop()
        self._schedule_model_warmup(loop)
        logger.info(
            "RabbitMQ task consumer started for queues=%s prefetch=%d",
            self.config.queues,
            self.config.prefetch_count,
        )
        channel.start_consuming()

    def _schedule_model_warmup(self, loop: asyncio.AbstractEventLoop) -> None:
        """Kick off background model warmup on the worker loop.

        MUST run on the same loop as task execution: the runtimes' asyncio
        locks/semaphores bind to the first loop that awaits them, so warming
        up on a throwaway loop would poison them for real tasks. Retain the
        future so it can't be GC'd mid-flight; warmup_models swallows
        per-runtime failures, so this never crashes the consumer.
        """
        if not settings.model_warmup_on_startup or self._warmup_future is not None:
            return
        from ai_worker.model_runtime.warmup import warmup_models

        self._warmup_future = asyncio.run_coroutine_threadsafe(warmup_models(), loop)

    def request_stop(self) -> None:
        """Signal-safe request to stop consuming.

        Safe to call from a signal handler or another thread:
        ``add_callback_threadsafe`` schedules ``stop_consuming`` on the pika
        I/O thread (the only thread allowed to touch the channel), which makes
        the blocking ``start_consuming()`` return so the caller can run the
        graceful ``stop()`` path. Falls back to a direct call when no live
        connection exists yet (e.g. signalled during startup).
        """
        conn = self._connection
        channel = self._channel
        if conn is not None and getattr(conn, "is_open", False) and channel is not None:
            conn.add_callback_threadsafe(channel.stop_consuming)
        elif channel is not None and getattr(channel, "is_open", False):
            channel.stop_consuming()

    def stop(self) -> None:
        if self._channel is not None and getattr(self._channel, "is_open", False):
            self._channel.stop_consuming()

        loop = self._loop
        if loop is not None and not loop.is_closed():
            # Let in-flight tasks finish (and their acks settle), then drain any
            # fire-and-forget background tasks (e.g. TOS backups) and close the
            # runtime ON the loop they were created on.
            try:
                self.wait_idle()
            except TimeoutError:
                logger.warning("consumer stop: in-flight tasks did not settle; proceeding")
            try:
                asyncio.run_coroutine_threadsafe(self._shutdown_loop(), loop).result(timeout=30)
            except Exception:  # noqa: BLE001 — shutdown must not raise
                logger.exception("consumer loop shutdown failed")
            loop.call_soon_threadsafe(loop.stop)
            if self._loop_thread is not None:
                self._loop_thread.join(timeout=10)
            if not loop.is_closed():
                loop.close()
            self._loop = None
            self._loop_thread = None
            self._close_connection()
        else:
            self._close_connection()
            asyncio.run(self.runtime.stop())

    async def _shutdown_loop(self) -> None:
        current = asyncio.current_task()
        pending = [t for t in asyncio.all_tasks() if t is not current]
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        await self.runtime.stop()

    def _close_connection(self) -> None:
        if self._connection is not None and getattr(self._connection, "is_open", False):
            # Flush any ack/reject callbacks scheduled via add_callback_threadsafe
            # before tearing the connection down so a just-finished task isn't
            # left unacked (and redelivered).
            try:
                self._connection.process_data_events(time_limit=1)
            except Exception:  # noqa: BLE001
                pass
            self._connection.close()

    # ── message handling ─────────────────────────────────────────────────────

    def _on_message(self, channel: Any, method: Any, _properties: Any, body: bytes) -> None:
        # Runs on the pika I/O thread — keep it cheap. Parse here (so malformed
        # JSON is rejected synchronously) and offload the actual work.
        try:
            raw_message = json.loads(body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.exception("invalid JSON task message; rejecting without requeue")
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
            return

        loop = self._ensure_loop()
        future = asyncio.run_coroutine_threadsafe(
            self.runtime.consume_message(raw_message), loop
        )
        with self._inflight_lock:
            self._inflight.add(future)

        def _on_done(fut: Future) -> None:
            # Runs on the worker loop thread when the task completes.
            self._settle(channel, method, fut)
            with self._inflight_lock:
                self._inflight.discard(fut)

        future.add_done_callback(_on_done)

    def _settle(self, channel: Any, method: Any, fut: Future) -> None:
        def _ack_or_reject() -> None:
            try:
                fut.result()
                channel.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:  # noqa: BLE001 — terminal: never requeue blindly
                logger.exception("task message failed; rejecting without requeue")
                channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)

        # A pika channel may only be touched from the I/O thread; hop back onto
        # it via add_callback_threadsafe. With no live connection (unit tests /
        # already shutting down) settle inline.
        conn = self._connection
        if conn is not None and getattr(conn, "is_open", False):
            conn.add_callback_threadsafe(_ack_or_reject)
        else:
            _ack_or_reject()
