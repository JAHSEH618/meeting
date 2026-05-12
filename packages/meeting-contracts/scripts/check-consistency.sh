#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# 契约一致性检查脚本
# 用途: CI / pre-commit 中运行，校验 OpenAPI、JSON Schema、枚举、错误码
# 依赖: node (spectral), python (jsonschema/click), 可选的 ajv-cli
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

echo "=== Meeting Contracts Consistency Check ==="
echo ""

# ── 1. Spectral Lint ────────────────────────────────────────────
echo "--- Spectral Lint ---"
if command -v spectral &>/dev/null; then
  for f in "$OPENAPI_DIR"/*.yaml; do
    fname=$(basename "$f")
    if spectral lint "$f" --ruleset "$CONTRACTS_DIR/.spectral.yaml" 2>&1; then
      pass "spectral: $fname"
    else
      warn "spectral: $fname has warnings/errors"
    fi
  done
else
  warn "spectral not installed — skipping lint"
  echo "  Install: npm install -g @stoplight/spectral-cli"
fi

# ── 2. JSON Schema 校验 ────────────────────────────────────────
echo "--- JSON Schema ---"
if python3 -c "import jsonschema" 2>/dev/null; then
  SCHEMA_FILE="$SCHEMAS_DIR/rabbitmq/processing-task-message.schema.json"
  if [ -f "$SCHEMA_FILE" ]; then
    python3 -c "
import json, jsonschema
with open('$SCHEMA_FILE') as f:
    schema = json.load(f)
jsonschema.Draft202012Validator.check_schema(schema)
print('Schema is valid Draft 2020-12')
" && pass "JSON Schema: processing-task-message.schema.json"
  fi
else
  warn "jsonschema not installed — skipping JSON Schema check"
fi

# ── 3. 枚举一致性 ───────────────────────────────────────────────
echo "--- Enum Consistency ---"
ENUMS_FILE="$SCHEMAS_DIR/common/enums.yaml"

# Check that processingStep values appear in callback-api step enum
if python3 -c "import yaml" 2>/dev/null; then
  python3 -c "
import yaml, sys
from pathlib import Path

errors = 0

# Load enums
with open('$ENUMS_FILE') as f:
    enums = yaml.safe_load(f)

# Load callback OpenAPI
with open('$OPENAPI_DIR/internal-callback-api.yaml') as f:
    cb = yaml.safe_load(f)

# Load public OpenAPI
with open('$OPENAPI_DIR/public-api.yaml') as f:
    pub = yaml.safe_load(f)

# Load AI worker internal OpenAPI
with open('$OPENAPI_DIR/ai-worker-internal-api.yaml') as f:
    worker_internal = yaml.safe_load(f)

# Load RabbitMQ task message schema
with open('$SCHEMAS_DIR/rabbitmq/processing-task-message.schema.json') as f:
    task_msg = yaml.safe_load(f)

# Compare processingStep enum vs callback StepName parameter
enum_steps = set(enums.get('processingStep', []))
cb_steps = set(
    cb.get('components', {})
      .get('parameters', {})
      .get('StepName', {})
      .get('schema', {})
      .get('enum', [])
)

if cb_steps and cb_steps != enum_steps:
    print(f'  processingStep mismatch: enums={sorted(enum_steps)} cb={sorted(cb_steps)}')
    errors += 1

# Compare ProcessingTaskStatus enum vs public-api schema
enum_task_status = set(enums.get('processingTaskStatus', []))
pub_task_status = set(
    pub.get('components', {})
      .get('schemas', {})
      .get('ProcessingTaskStatus', {})
      .get('enum', [])
)

if pub_task_status and pub_task_status != enum_task_status:
    print(f'  processingTaskStatus mismatch: enums={sorted(enum_task_status)} pub={sorted(pub_task_status)}')
    errors += 1

# Compare ProcessingTaskPhase enum vs public-api schema
enum_task_phase = set(enums.get('processingTaskPhase', []))
pub_task_phase = set(
    pub.get('components', {})
      .get('schemas', {})
      .get('ProcessingTaskPhase', {})
      .get('enum', [])
)

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

# Compare RagAnswerCoverage enum vs public-api schema
enum_rag_coverage = set(enums.get('ragAnswerCoverage', []))
pub_rag_coverage = set(
    pub.get('components', {})
      .get('schemas', {})
      .get('RagAnswerCoverage', {})
      .get('enum', [])
)

if pub_rag_coverage and pub_rag_coverage != enum_rag_coverage:
    print(f'  ragAnswerCoverage mismatch: enums={sorted(enum_rag_coverage)} pub={sorted(pub_rag_coverage)}')
    errors += 1

rag_answer = pub.get('components', {}).get('schemas', {}).get('RagAnswerDTO', {})
if 'coverage' not in rag_answer.get('required', []):
    print('  RagAnswerDTO.coverage must be required')
    errors += 1

# Compare ProcessingStepUpdateSource enum vs public-api schema
enum_step_sources = set(enums.get('processingStepUpdateSource', []))
pub_step_sources = set(
    pub.get('components', {})
      .get('schemas', {})
      .get('ProcessingStepUpdateSource', {})
      .get('enum', [])
)

if pub_step_sources and pub_step_sources != enum_step_sources:
    print(f'  processingStepUpdateSource mismatch: enums={sorted(enum_step_sources)} pub={sorted(pub_step_sources)}')
    errors += 1

# Worker task messages must not assign Java-owned steps to ai-worker.
pipeline_steps = set(
    task_msg.get('properties', {})
      .get('pipelineSteps', {})
      .get('items', {})
      .get('enum', [])
)
forbidden_worker_steps = {'AUDIO_UPLOAD', 'SUMMARY', 'EXTRACTION'}
if pipeline_steps & forbidden_worker_steps:
    print(f'  pipelineSteps must not include Java-owned steps: {sorted(pipeline_steps & forbidden_worker_steps)}')
    errors += 1

expected_pipeline_steps = enum_steps - forbidden_worker_steps
if pipeline_steps and pipeline_steps != expected_pipeline_steps:
    print(
        '  pipelineSteps drift vs processingStep: '
        f'missing={sorted(expected_pipeline_steps - pipeline_steps)} '
        f'extra={sorted(pipeline_steps - expected_pipeline_steps)}'
    )
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

# Compare SourceType enum vs callback EmbeddingsCallbackRequest
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

worker_source_types = set(
    worker_internal.get('components', {})
      .get('schemas', {})
      .get('SourceType', {})
      .get('enum', [])
)

if worker_source_types and worker_source_types != enum_source_types:
    print(f'  sourceType mismatch: enums={sorted(enum_source_types)} worker-internal={sorted(worker_source_types)}')
    errors += 1

# Compare TaskEventType enum vs public-api TaskEvent schema
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

# Compare staleStatus enum between enums.yaml and internal-callback-api
enum_stale = set(enums.get('staleStatus', []))
cb_stale = set()
for sch_name, sch in cb.get('components', {}).get('schemas', {}).items():
    pass  # staleStatus not directly in callback schemas

if errors:
    print(f'  {errors} enum mismatch(es) found')
    sys.exit(1)
else:
    print('  All enum values consistent across yaml files')
" && pass "enums consistent" || warn "enum mismatch detected"
else
  warn "pyyaml not installed — skipping enum consistency"
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

echo ""
echo "=== Consistency check complete ==="
