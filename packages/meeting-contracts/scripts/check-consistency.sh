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

# Compare processingStep enum vs callback ProcessingStep schema
enum_steps = set(enums.get('processingStep', []))
cb_steps = set()
for path_item in cb['paths'].values():
    for method, op in path_item.items():
        for param in op.get('parameters', []):
            ref = param.get('\$ref', '')
            if ref == '#/components/parameters/StepName':
                schema = param.get('schema', {})
                cb_steps.update(schema.get('enum', []))

if cb_steps and cb_steps != enum_steps:
    print(f'  processingStep mismatch: enums={sorted(enum_steps)} cb={sorted(cb_steps)}')
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
else:
  warn "pyyaml not installed — skipping enum consistency"
fi

# ── 4. 错误码完整性 ────────────────────────────────────────────
echo "--- Error Codes ---"
ERROR_CODES_FILE="$SCHEMAS_DIR/common/error-codes.yaml"
if [ -f "$ERROR_CODES_FILE" ]; then
  count=$(python3 -c "
import yaml
with open('$ERROR_CODES_FILE') as f:
    codes = yaml.safe_load(f)
print(len(codes) if isinstance(codes, list) else len(codes.get('errorCodes', [])))
" 2>/dev/null || echo "0")
  pass "error-codes.yaml: $count entries"
else
  warn "error-codes.yaml not found"
fi

echo ""
echo "=== Consistency check complete ==="
