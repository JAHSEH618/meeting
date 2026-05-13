from __future__ import annotations

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime


def run() -> None:
    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    RabbitMqTaskConsumer(runtime).start_consuming()
