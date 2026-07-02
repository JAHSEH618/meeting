from __future__ import annotations

import logging
import signal
import threading
import time

import pika.exceptions

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import validate_runtime_config, validate_security_config
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime

logger = logging.getLogger(__name__)

_RECONNECT_INITIAL_SECONDS = 1.0
_RECONNECT_MAX_SECONDS = 30.0
# A connection that survived this long counts as healthy: reset the backoff so
# a fresh outage starts from the short delay again.
_RECONNECT_HEALTHY_SECONDS = 60.0


def run() -> None:
    # The consumer signs outbound callbacks with callback_hmac_secret; refuse to
    # start with the shipped default (admin JWT not required for the consumer).
    validate_security_config(require_admin=False)
    # Production storage/checksum config + fake model runtimes is the most
    # dangerous misconfiguration in the system (green pipeline, placeholder
    # transcripts) — refuse to start unless explicitly acknowledged.
    validate_runtime_config()
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    consumer = RabbitMqTaskConsumer(runtime)

    # On SIGTERM/SIGINT (K8s pod termination, Ctrl-C) ask the consumer to stop
    # consuming so start_consuming() returns and the finally block runs the
    # graceful-shutdown path (drain in-flight tasks + their acks, await
    # fire-and-forget TOS backups, close httpx clients). Without this, the
    # default handler kills the process mid-task: unacked messages are
    # redelivered (duplicate inference) and background uploads/clients leak.
    stop_requested = threading.Event()

    def _handle_signal(signum: int, _frame: object) -> None:
        logger.info("received signal %s; requesting graceful consumer shutdown", signum)
        stop_requested.set()
        consumer.request_stop()

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    # Reconnect loop: a broker restart / network blip used to bubble out of
    # start_consuming() and kill the whole process — losing every lazily
    # loaded model singleton, so the next task also paid a full cold start.
    # Reconnecting here rebuilds only the AMQP connection/channel; the worker
    # event loop, runtimes and HTTP pools stay warm.
    backoff = _RECONNECT_INITIAL_SECONDS
    try:
        while not stop_requested.is_set():
            started_at = time.monotonic()
            try:
                consumer.start_consuming()
                break  # returned normally — stop was requested
            except (pika.exceptions.AMQPError, OSError) as exc:
                if stop_requested.is_set():
                    break
                if time.monotonic() - started_at >= _RECONNECT_HEALTHY_SECONDS:
                    backoff = _RECONNECT_INITIAL_SECONDS
                logger.warning(
                    "broker connection lost (%s: %s); reconnecting in %.1fs",
                    type(exc).__name__,
                    exc,
                    backoff,
                )
                stop_requested.wait(backoff)
                backoff = min(backoff * 2, _RECONNECT_MAX_SECONDS)
    finally:
        consumer.stop()
