from __future__ import annotations

import logging
import signal

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import validate_security_config
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime

logger = logging.getLogger(__name__)


def run() -> None:
    # The consumer signs outbound callbacks with callback_hmac_secret; refuse to
    # start with the shipped default (admin JWT not required for the consumer).
    validate_security_config(require_admin=False)
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    consumer = RabbitMqTaskConsumer(runtime)

    # On SIGTERM/SIGINT (K8s pod termination, Ctrl-C) ask the consumer to stop
    # consuming so start_consuming() returns and the finally block runs the
    # graceful-shutdown path (drain in-flight tasks + their acks, await
    # fire-and-forget TOS backups, close httpx clients). Without this, the
    # default handler kills the process mid-task: unacked messages are
    # redelivered (duplicate inference) and background uploads/clients leak.
    def _handle_signal(signum: int, _frame: object) -> None:
        logger.info("received signal %s; requesting graceful consumer shutdown", signum)
        consumer.request_stop()

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    try:
        consumer.start_consuming()
    finally:
        consumer.stop()
