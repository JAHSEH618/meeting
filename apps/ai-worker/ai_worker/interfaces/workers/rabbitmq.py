from __future__ import annotations

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.common.config import settings
from ai_worker.common.secret_guard import assert_secrets_configured
from ai_worker.infrastructure.mq.rabbitmq_consumer import RabbitMqTaskConsumer
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime


def run() -> None:
    # Phase J I7 — fail closed on dev-default secrets outside dev
    assert_secrets_configured(settings)

    runtime = MvpWorkerRuntime(state_store=workflow_state_store)
    RabbitMqTaskConsumer(runtime).start_consuming()
