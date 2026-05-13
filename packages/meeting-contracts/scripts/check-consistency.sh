#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# 契约一致性检查脚本
# 用途: CI / pre-commit 中运行，校验 OpenAPI、JSON Schema、枚举、错误码
# 依赖: node (spectral), python (jsonschema/pyyaml), 可选的 ajv-cli
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONTRACTS_DIR="$PROJECT_ROOT/packages/meeting-contracts"
OPENAPI_DIR="$CONTRACTS_DIR/openapi"
SCHEMAS_DIR="$CONTRACTS_DIR/schemas"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $1"; }
warn() { echo -e "${YELLOW}⚠${NC} $1"; }
fail() { echo -e "${RED}✗${NC} $1"; exit 1; }
error_exit() { echo -e "${RED}✗${NC} $1"; HAS_ERRORS=1; }

echo "=== Meeting Contracts Consistency Check ==="
echo ""

HAS_ERRORS=0

# ── 1. Spectral Lint ────────────────────────────────────────────
echo "--- Spectral Lint ---"
if command -v npx &>/dev/null && [ -d "$CONTRACTS_DIR/node_modules" ]; then
  pub_result=0
  npx spectral lint "$OPENAPI_DIR/public-api.yaml" --ruleset "$CONTRACTS_DIR/.spectral-public.yaml" --fail-severity warn 2>&1 || pub_result=1
  if [ $pub_result -eq 0 ]; then
    pass "spectral: public-api.yaml"
  else
    error_exit "spectral: public-api.yaml has errors"
  fi

  cb_result=0
  npx spectral lint "$OPENAPI_DIR/internal-callback-api.yaml" --ruleset "$CONTRACTS_DIR/.spectral-callback.yaml" --fail-severity warn 2>&1 || cb_result=1
  if [ $cb_result -eq 0 ]; then
    pass "spectral: internal-callback-api.yaml"
  else
    error_exit "spectral: internal-callback-api.yaml has errors"
  fi

  wk_result=0
  npx spectral lint "$OPENAPI_DIR/ai-worker-internal-api.yaml" --ruleset "$CONTRACTS_DIR/.spectral-public.yaml" --fail-severity warn 2>&1 || wk_result=1
  if [ $wk_result -eq 0 ]; then
    pass "spectral: ai-worker-internal-api.yaml"
  else
    error_exit "spectral: ai-worker-internal-api.yaml has errors"
  fi
elif command -v spectral &>/dev/null; then
  for f in "$OPENAPI_DIR"/*.yaml; do
    fname=$(basename "$f")
    case "$fname" in
      public-api.yaml) ruleset="$CONTRACTS_DIR/.spectral-public.yaml" ;;
      internal-callback-api.yaml) ruleset="$CONTRACTS_DIR/.spectral-callback.yaml" ;;
      *) ruleset="$CONTRACTS_DIR/.spectral-public.yaml" ;;
    esac
    if spectral lint "$f" --ruleset "$ruleset" --fail-severity warn 2>&1; then
      pass "spectral: $fname"
    else
      error_exit "spectral: $fname has errors"
    fi
  done
else
  fail "spectral not installed — cannot verify OpenAPI contracts. Install: npm install -g @stoplight/spectral-cli"
fi

# ── 2. JSON Schema 校验 ────────────────────────────────────────
echo "--- JSON Schema ---"
if python3 -c "import jsonschema" 2>/dev/null; then
  for SCHEMA_FILE in "$SCHEMAS_DIR"/rabbitmq/*.schema.json; do
    if [ -f "$SCHEMA_FILE" ]; then
      python3 -c "
import json, jsonschema
with open('$SCHEMA_FILE') as f:
    schema = json.load(f)
jsonschema.Draft202012Validator.check_schema(schema)
print('Schema is valid Draft 2020-12')
" && pass "JSON Schema: $(basename "$SCHEMA_FILE")"
    fi
  done
else
  fail "jsonschema not installed — cannot verify Draft 2020-12 schemas. Install: pip install jsonschema"
fi

# ── 3. 枚举一致性 ───────────────────────────────────────────────
echo "--- Enum Consistency ---"
ENUMS_FILE="$SCHEMAS_DIR/common/enums.yaml"

if python3 -c "import yaml" 2>/dev/null; then
  python3 -c "
import yaml, sys
from pathlib import Path

errors = 0

with open('$ENUMS_FILE') as f:
    enums = yaml.safe_load(f)

with open('$OPENAPI_DIR/internal-callback-api.yaml') as f:
    cb = yaml.safe_load(f)
with open('$OPENAPI_DIR/public-api.yaml') as f:
    pub = yaml.safe_load(f)
with open('$OPENAPI_DIR/ai-worker-internal-api.yaml') as f:
    worker_internal = yaml.safe_load(f)
with open('$SCHEMAS_DIR/rabbitmq/processing-task-message.schema.json') as f:
    import json
    task_msg = json.load(f)

enum_steps = set(enums.get('processingStep', []))
cb_steps = set(cb.get('components', {}).get('parameters', {}).get('StepName', {}).get('schema', {}).get('enum', []))
if cb_steps and cb_steps != enum_steps:
    print(f'  processingStep mismatch: enums={sorted(enum_steps)} cb={sorted(cb_steps)}')
    errors += 1

enum_task_status = set(enums.get('processingTaskStatus', []))
pub_task_status = set(pub.get('components', {}).get('schemas', {}).get('ProcessingTaskStatus', {}).get('enum', []))
if pub_task_status and pub_task_status != enum_task_status:
    print(f'  processingTaskStatus mismatch: enums={sorted(enum_task_status)} pub={sorted(pub_task_status)}')
    errors += 1

enum_task_phase = set(enums.get('processingTaskPhase', []))
pub_task_phase = set(pub.get('components', {}).get('schemas', {}).get('ProcessingTaskPhase', {}).get('enum', []))
if pub_task_phase and pub_task_phase != enum_task_phase:
    print(f'  processingTaskPhase mismatch: enums={sorted(enum_task_phase)} pub={sorted(pub_task_phase)}')
    errors += 1

processing_task = pub.get('components', {}).get('schemas', {}).get('ProcessingTask', {})
if 'phase' not in processing_task.get('required', []):
    print('  ProcessingTask.phase must be required')
    errors += 1
phase_schema = processing_task.get('properties', {}).get('phase', {})
if phase_schema.get('\$ref') != '#/components/schemas/ProcessingTaskPhase':
    print('  ProcessingTask.phase must directly reference ProcessingTaskPhase')
    errors += 1

enum_rag_coverage = set(enums.get('ragAnswerCoverage', []))
pub_rag_coverage = set(pub.get('components', {}).get('schemas', {}).get('RagAnswerCoverage', {}).get('enum', []))
if pub_rag_coverage and pub_rag_coverage != enum_rag_coverage:
    print(f'  ragAnswerCoverage mismatch: enums={sorted(enum_rag_coverage)} pub={sorted(pub_rag_coverage)}')
    errors += 1
rag_answer = pub.get('components', {}).get('schemas', {}).get('RagAnswerDTO', {})
if 'coverage' not in rag_answer.get('required', []):
    print('  RagAnswerDTO.coverage must be required')
    errors += 1

enum_step_sources = set(enums.get('processingStepUpdateSource', []))
pub_step_sources = set(pub.get('components', {}).get('schemas', {}).get('ProcessingStepUpdateSource', {}).get('enum', []))
if pub_step_sources and pub_step_sources != enum_step_sources:
    print(f'  processingStepUpdateSource mismatch: enums={sorted(enum_step_sources)} pub={sorted(pub_step_sources)}')
    errors += 1

pipeline_steps = set(task_msg.get('properties', {}).get('pipelineSteps', {}).get('items', {}).get('enum', []))
task_types = set(task_msg.get('properties', {}).get('taskType', {}).get('enum', []))
if 'EXPORT' in task_types:
    print('  processing-task-message.schema.json must not include EXPORT taskType')
    errors += 1
forbidden_worker_steps = {'AUDIO_UPLOAD', 'SUMMARY', 'EXTRACTION', 'EXPORT'}
if pipeline_steps & forbidden_worker_steps:
    print(f'  pipelineSteps must not include non-ai-worker steps: {sorted(pipeline_steps & forbidden_worker_steps)}')
    errors += 1
expected_pipeline_steps = enum_steps - forbidden_worker_steps
if pipeline_steps and pipeline_steps != expected_pipeline_steps:
    print(f'  pipelineSteps drift: missing={sorted(expected_pipeline_steps - pipeline_steps)} extra={sorted(pipeline_steps - expected_pipeline_steps)}')
    errors += 1

task_step = pub.get('components', {}).get('schemas', {}).get('ProcessingTaskStep', {})
if 'source' not in task_step.get('required', []):
    print('  ProcessingTaskStep.source must be required')
    errors += 1
source_schema = task_step.get('properties', {}).get('source', {})
if source_schema.get('\$ref') != '#/components/schemas/ProcessingStepUpdateSource':
    print('  ProcessingTaskStep.source must directly reference ProcessingStepUpdateSource')
    errors += 1

complete_req = cb.get('components', {}).get('schemas', {}).get('CompleteWorkerPhaseRequest', {})
if 'phase' not in complete_req.get('required', []):
    print('  CompleteWorkerPhaseRequest.phase must be required')
    errors += 1
phase_values = set(complete_req.get('properties', {}).get('phase', {}).get('enum', []))
if phase_values != {'WORKER_DAG'}:
    print(f'  CompleteWorkerPhaseRequest.phase must only allow WORKER_DAG, got {sorted(phase_values)}')
    errors += 1

enum_source_types = set(enums.get('sourceType', []))
cb_source_types = set()
for sch_name, sch in cb.get('components', {}).get('schemas', {}).items():
    if sch_name == 'EmbeddingsCallbackRequest':
        for prop_name, prop in sch.get('properties', {}).items():
            if prop_name == 'sourceType':
                cb_source_types.update(prop.get('enum', []))
if cb_source_types and cb_source_types != enum_source_types:
    print(f'  sourceType mismatch: enums={sorted(enum_source_types)} cb={sorted(cb_source_types)}')
    errors += 1
worker_source_types = set(worker_internal.get('components', {}).get('schemas', {}).get('SourceType', {}).get('enum', []))
if worker_source_types and worker_source_types != enum_source_types:
    print(f'  sourceType mismatch: enums={sorted(enum_source_types)} worker-internal={sorted(worker_source_types)}')
    errors += 1

enum_task_events = set(enums.get('taskEventType', []))
pub_task_events = set()
for sch_name, sch in pub.get('components', {}).get('schemas', {}).items():
    if sch_name == 'TaskEvent':
        for prop_name, prop in sch.get('properties', {}).items():
            if prop_name == 'eventType':
                pub_task_events.update(prop.get('enum', []))
if pub_task_events and pub_task_events != enum_task_events:
    print(f'  taskEventType mismatch: enums={sorted(enum_task_events)} pub={sorted(pub_task_events)}')
    errors += 1

if errors:
    print(f'  {errors} enum mismatch(es) found')
    sys.exit(1)
else:
    print('  All enum values consistent across yaml files')
" && pass "enums consistent" || { error_exit "enum mismatch detected"; }
else
  fail "pyyaml not installed — cannot verify enum consistency. Install: pip install pyyaml"
fi

# ── 4. 错误码完整性 ────────────────────────────────────────────
echo "--- Error Codes ---"
ERROR_CODES_FILE="$SCHEMAS_DIR/common/error-codes.yaml"
if [ -f "$ERROR_CODES_FILE" ]; then
  if python3 -c "import yaml" 2>/dev/null; then
    count=$(python3 -c "
import yaml
with open('$ERROR_CODES_FILE') as f:
    codes = yaml.safe_load(f)
print(len(codes) if isinstance(codes, list) else len(codes.get('errorCodes', [])))
" 2>/dev/null)
    pass "error-codes.yaml: $count entries"
  else
    warn "pyyaml not installed — skipping error code count"
  fi
else
  warn "error-codes.yaml not found"
fi

# ── 5. Fixture Validation ─────────────────────────────────────────
echo "--- Fixture Validation ---"
FIXTURES_DIR="$CONTRACTS_DIR/fixtures"
if python3 -c "import jsonschema" 2>/dev/null && [ -d "$FIXTURES_DIR" ]; then
  python3 -c "
import json, jsonschema, sys, os
from pathlib import Path

schema_map = {
    'processing-task-message.schema.json': [
        'valid/processing-task-meeting-full-pipeline.json',
        'valid/processing-task-speaker-enrollment.json',
        'valid/processing-task-text-embedding.json',
        'valid/processing-task-rag-reindex.json',
        'invalid/processing-task-meeting-null-meetingid.json',
        'invalid/processing-task-forbidden-worker-steps.json',
        'invalid/processing-task-text-embedding-no-id.json',
        'invalid/processing-task-speaker-enrollment-missing-fields.json',
    ],
    'export-job-message.schema.json': [
        'valid/export-job-message.json',
        'invalid/export-job-invalid-format.json',
    ]
}

errors = 0
for schema_file, fixture_paths in schema_map.items():
    schema_path = Path('$SCHEMAS_DIR/rabbitmq') / schema_file
    if not schema_path.exists():
        print(f'  FAIL schema not found: {schema_file}')
        errors += 1
        continue
    with open(schema_path) as f:
        schema = json.load(f)
    validator = jsonschema.Draft202012Validator(schema)
    for fp in fixture_paths:
        fixture_path = Path('$FIXTURES_DIR') / fp
        if not fixture_path.exists():
            print(f'  FAIL fixture not found: {fp}')
            errors += 1
            continue
        with open(fixture_path) as f:
            instance = json.load(f)
        is_valid = validator.is_valid(instance)
        is_invalid_fixture = fp.startswith('invalid/')
        if is_invalid_fixture:
            if is_valid:
                print(f'  FAIL {fp}: expected validation error but schema accepted it')
                errors += 1
            else:
                print(f'  OK   {fp}: correctly rejected')
        else:
            if not is_valid:
                errs = list(validator.iter_errors(instance))
                print(f'  FAIL {fp}: schema validation failed')
                for e in errs[:3]:
                    print(f'       {e.json_path}: {e.message}')
                errors += 1
            else:
                print(f'  OK   {fp}: valid')

if errors:
    print(f'  {errors} fixture validation error(s) found')
    sys.exit(1)
else:
    print('  All fixtures validated successfully')
" || HAS_ERRORS=1
else
  fail "jsonschema not installed or fixtures directory missing — cannot verify fixtures"
fi

# ── 6. OpenAPI Fixture Validation ───────────────────────────────
echo "--- OpenAPI Fixture Validation ---"
if python3 -c "import jsonschema,yaml" 2>/dev/null && [ -d "$FIXTURES_DIR" ]; then
  python3 "$(dirname "$0")/check-openapi-fixtures.py" || HAS_ERRORS=1
else
  fail "jsonschema or pyyaml not installed, or fixtures directory missing — cannot verify OpenAPI fixtures"
fi

# ── 7. Hand-written vs Generated DTO structural consistency ──────
echo "--- Hand-written / Generated DTO Consistency ---"
python3 "$(dirname "$0")/check-export-job-dto.py" || HAS_ERRORS=1

echo ""
if [ "$HAS_ERRORS" -ne 0 ]; then
  echo -e "${RED}=== Consistency check FAILED ===${NC}"
  exit 1
else
  echo -e "${GREEN}=== Consistency check complete ===${NC}"
fi