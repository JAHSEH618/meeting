import json
from dataclasses import dataclass
from pathlib import Path

import jsonschema

_SCHEMA_PATH = Path(__file__).parent.parent / "schemas" / "rabbitmq" / "processing-task-message.schema.json"

if _SCHEMA_PATH.exists():
    with open(_SCHEMA_PATH) as f:
        _TASK_SCHEMA = json.load(f)
else:
    _TASK_SCHEMA = None


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    errors: list[str]

    @property
    def error_code(self) -> str:
        return "INVALID_TASK_MESSAGE" if not self.valid and self.errors else ""


def validate_task_message(message: dict) -> ValidationResult:
    if _TASK_SCHEMA is None:
        return ValidationResult(valid=True, errors=[])
    validator = jsonschema.Draft202012Validator(_TASK_SCHEMA)
    errors = []
    for e in sorted(validator.iter_errors(message), key=lambda e: e.json_path):
        errors.append(f"{e.json_path}: {e.message}")
    return ValidationResult(valid=len(errors) == 0, errors=errors)


def validate_pipeline_steps(task_type: str, pipeline_steps: list[str]) -> ValidationResult:
    from ai_worker.application.workflows.registry import (
        WORKFLOW_STEPS_BY_TASK_TYPE,
        JAVA_OWNED_STEPS,
    )
    expected = set(WORKFLOW_STEPS_BY_TASK_TYPE.get(task_type, ()))
    actual = set(pipeline_steps)

    errors: list[str] = []

    forbidden = actual & JAVA_OWNED_STEPS
    if forbidden:
        errors.append(f"pipelineSteps contains Java-owned steps: {sorted(forbidden)}")

    if "EXPORT" in actual:
        errors.append("pipelineSteps must not include EXPORT; use export-job-message")

    unexpected = actual - expected
    if unexpected:
        errors.append(f"pipelineSteps contains unexpected steps for taskType={task_type}: {sorted(unexpected)}")

    missing = expected - actual
    if missing:
        errors.append(f"pipelineSteps missing expected steps for taskType={task_type}: {sorted(missing)}")

    return ValidationResult(valid=len(errors) == 0, errors=errors)
