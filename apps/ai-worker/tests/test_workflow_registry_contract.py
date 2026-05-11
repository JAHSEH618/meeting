import json
from pathlib import Path

from ai_worker.application.workflows.registry import (
    JAVA_OWNED_STEPS,
    WORKFLOW_STEPS_BY_TASK_TYPE,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
PROCESSING_TASK_MESSAGE_SCHEMA = (
    REPO_ROOT
    / "packages"
    / "meeting-contracts"
    / "schemas"
    / "rabbitmq"
    / "processing-task-message.schema.json"
)


def _schema_pipeline_steps() -> set[str]:
    schema = json.loads(PROCESSING_TASK_MESSAGE_SCHEMA.read_text())
    return set(schema["properties"]["pipelineSteps"]["items"]["enum"])


def _schema_task_types() -> set[str]:
    schema = json.loads(PROCESSING_TASK_MESSAGE_SCHEMA.read_text())
    return set(schema["properties"]["taskType"]["enum"])


def _registry_steps() -> set[str]:
    return set().union(*WORKFLOW_STEPS_BY_TASK_TYPE.values())


def test_registry_steps_are_allowed_by_task_message_schema() -> None:
    assert _registry_steps() <= _schema_pipeline_steps()


def test_registry_task_types_match_schema() -> None:
    assert set(WORKFLOW_STEPS_BY_TASK_TYPE.keys()) == _schema_task_types()


def test_java_owned_steps_are_not_worker_owned() -> None:
    assert JAVA_OWNED_STEPS.isdisjoint(_schema_pipeline_steps())
    assert all(
        JAVA_OWNED_STEPS.isdisjoint(steps)
        for steps in WORKFLOW_STEPS_BY_TASK_TYPE.values()
    )
