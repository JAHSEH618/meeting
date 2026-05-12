import json
from dataclasses import dataclass
from pathlib import Path

import jsonschema

_SCHEMAS_DIR = Path(__file__).parent.parent / "schemas"
_TASK_SCHEMA_PATH = _SCHEMAS_DIR / "rabbitmq" / "processing-task-message.schema.json"

_TASK_SCHEMA: dict | None = None
_SCHEMA_LOADED = False


def _load_schema() -> dict | None:
    global _TASK_SCHEMA, _SCHEMA_LOADED
    if _SCHEMA_LOADED:
        return _TASK_SCHEMA
    _SCHEMA_LOADED = True
    if _TASK_SCHEMA_PATH.exists():
        with open(_TASK_SCHEMA_PATH) as f:
            _TASK_SCHEMA = json.load(f)
    return _TASK_SCHEMA


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    errors: list[str]

    @property
    def error_code(self) -> str:
        return "INVALID_TASK_MESSAGE" if not self.valid and self.errors else ""


def validate_task_message(message: dict) -> ValidationResult:
    schema = _load_schema()
    if schema is None:
        return ValidationResult(
            valid=False,
            errors=["SCHEMA_NOT_FOUND: processing-task-message.schema.json missing; failing closed"],
        )
    validator = jsonschema.Draft202012Validator(schema)
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