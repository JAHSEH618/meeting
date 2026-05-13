#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Check that generated code is in sync with contracts.
# Generates to a temp directory (.generated-check/) and diffs against committed
# files. Does NOT modify the working tree — safe for read-only environments.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$CONTRACTS_DIR/../.." && pwd)"
TEMP_DIR="$CONTRACTS_DIR/.generated-check"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; }

codegen_failures=0
drift_found=0

# ── Preflight ────────────────────────────────────────────────────────────────
rm -rf "$TEMP_DIR"
if ! mkdir -p "$TEMP_DIR" 2>/dev/null; then
  echo "  FAIL: cannot create temp directory $TEMP_DIR"
  echo "  Check permissions on packages/meeting-contracts/"
  exit 1
fi

echo "  Regenerating codegen to $TEMP_DIR ..."

# ── Diff helpers ─────────────────────────────────────────────────────────────
diff_file() {
  # Compare a single generated file against its committed counterpart
  local temp_file="$1"
  local real_file="$2"
  local label="$3"
  if [ ! -f "$temp_file" ]; then return; fi
  if git diff --no-index --quiet "$real_file" "$temp_file" 2>/dev/null; then
    return 0
  else
    fail "$label — $real_file differs"
    drift_found=1
    return 1
  fi
}

diff_path() {
  # Compare a temp directory tree against a committed directory tree
  local temp_path="$1"
  local real_path="$2"
  local label="$3"
  if [ ! -e "$temp_path" ]; then
    fail "$label: codegen did not produce output"
    drift_found=1
    return
  fi
  if git diff --no-index --quiet "$real_path" "$temp_path" 2>/dev/null; then
    pass "$label in sync"
    return
  fi
  # Directory-level drift: report first few differing files
  fail "$label DRIFT — generated output differs from committed files"
  git diff --no-index --stat "$real_path" "$temp_path" 2>/dev/null | tail -3
  drift_found=1
}

diff_java_models() {
  # Compare only the model .java files that exist in the committed tree.
  # Ignores auxiliary files (auth, ApiClient, tests) that vary by generator version.
  local temp_dir="$1"
  local real_dir="$2"
  local label="$3"

  local count=0
  local mismatches=0
  while IFS= read -r -d '' real_file; do
    local rel="${real_file#$real_dir/}"
    local temp_file="$temp_dir/$rel"
    count=$((count + 1))
    if [ -f "$temp_file" ]; then
      if ! git diff --no-index --quiet "$real_file" "$temp_file" 2>/dev/null; then
        fail "$label — $rel differs"
        mismatches=$((mismatches + 1))
        drift_found=1
      fi
    else
      fail "$label — $rel missing from generated output"
      mismatches=$((mismatches + 1))
      drift_found=1
    fi
  done < <(find "$real_dir" -name "*.java" -type f -print0 2>/dev/null)

  if [ "$mismatches" -eq 0 ]; then
    pass "$label in sync ($count model files)"
  fi
}

_err=$(mktemp)
trap "rm -f $_err; rm -rf $TEMP_DIR" EXIT

# ── TS ───────────────────────────────────────────────────────────────────────
if npx openapi-typescript "$CONTRACTS_DIR/openapi/public-api.yaml" \
    -o "$TEMP_DIR/types.gen.ts" >/dev/null 2>$_err; then
  pass "codegen:ts"
  diff_path "$TEMP_DIR/types.gen.ts" \
    "$PROJECT_ROOT/apps/meeting-web/src/shared/api/types.gen.ts" \
    "TS types.gen.ts"
else
  fail "codegen:ts FAILED"
  cat $_err >&2
  codegen_failures=1
fi

# ── Java: public-api ─────────────────────────────────────────────────────────
JAVA_PUB_TEMP="$TEMP_DIR/java-public"
if npx @openapitools/openapi-generator-cli generate -g java \
    -i "$CONTRACTS_DIR/openapi/public-api.yaml" \
    -o "$JAVA_PUB_TEMP" \
    --additional-properties=apiPackage=com.meeting.api.client.publicapi,modelPackage=com.meeting.api.client.publicapi.model,hideGenerationTimestamp=true \
    >/dev/null 2>$_err; then
  # Strip the *Api.java client wrappers and test files (same as cleanup-java-codegen.sh)
  find "$JAVA_PUB_TEMP" -name "*Api.java" -delete 2>/dev/null || true
  find "$JAVA_PUB_TEMP" -path "*/test/*" -delete 2>/dev/null || true
  pass "codegen:java-public"
  diff_java_models "$JAVA_PUB_TEMP/src/main/java" \
    "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/public-api/src/main/java" \
    "Java public-api"
else
  fail "codegen:java-public FAILED"
  cat $_err >&2
  codegen_failures=1
fi

# ── Java: ai-worker-internal ─────────────────────────────────────────────────
JAVA_WK_TEMP="$TEMP_DIR/java-worker-internal"
if npx @openapitools/openapi-generator-cli generate -g java \
    -i "$CONTRACTS_DIR/openapi/ai-worker-internal-api.yaml" \
    -o "$JAVA_WK_TEMP" \
    --additional-properties=apiPackage=com.meeting.api.client.workerinternal,modelPackage=com.meeting.api.client.workerinternal.model,hideGenerationTimestamp=true \
    >/dev/null 2>$_err; then
  find "$JAVA_WK_TEMP" -name "*Api.java" -delete 2>/dev/null || true
  find "$JAVA_WK_TEMP" -path "*/test/*" -delete 2>/dev/null || true
  pass "codegen:java-worker-internal"
  diff_java_models "$JAVA_WK_TEMP/src/main/java" \
    "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/ai-worker-internal/src/main/java" \
    "Java ai-worker-internal"
else
  fail "codegen:java-worker-internal FAILED"
  cat $_err >&2
  codegen_failures=1
fi

# ── Java: export-job ─────────────────────────────────────────────────────────
if [ -f "$CONTRACTS_DIR/openapi/export-job-message.yaml" ]; then
  JAVA_EXP_TEMP="$TEMP_DIR/java-export-job"
  if npx @openapitools/openapi-generator-cli generate -g java \
      -i "$CONTRACTS_DIR/openapi/export-job-message.yaml" \
      -o "$JAVA_EXP_TEMP" \
      --additional-properties=apiPackage=com.meeting.api.client.exportjob,modelPackage=com.meeting.api.client.exportjob.model,hideGenerationTimestamp=true \
      >/dev/null 2>$_err; then
    find "$JAVA_EXP_TEMP" -name "*Api.java" -delete 2>/dev/null || true
    find "$JAVA_EXP_TEMP" -path "*/test/*" -delete 2>/dev/null || true
    pass "codegen:java-export-job"
    diff_java_models "$JAVA_EXP_TEMP/src/main/java" \
      "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/export-job/src/main/java" \
      "Java export-job"
  else
    fail "codegen:java-export-job FAILED"
    cat $_err >&2
    codegen_failures=1
  fi
fi

# ── Python ───────────────────────────────────────────────────────────────────
if command -v datamodel-codegen &>/dev/null || python3 -c "import datamodel_code_generator" 2>/dev/null; then
  # callback
  if datamodel-codegen --input "$CONTRACTS_DIR/openapi/internal-callback-api.yaml" \
      --input-file-type openapi --output "$TEMP_DIR/internal_callback_types.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-callback"
    diff_path "$TEMP_DIR/internal_callback_types.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/internal_callback_types.py" \
      "Python internal_callback_types.py"
  else
    fail "codegen:py-callback FAILED"
    cat $_err >&2
    codegen_failures=1
  fi

  # worker-internal
  if datamodel-codegen --input "$CONTRACTS_DIR/openapi/ai-worker-internal-api.yaml" \
      --input-file-type openapi --output "$TEMP_DIR/ai_worker_internal_types.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-worker-internal"
    diff_path "$TEMP_DIR/ai_worker_internal_types.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/ai_worker_internal_types.py" \
      "Python ai_worker_internal_types.py"
  else
    fail "codegen:py-worker-internal FAILED"
    cat $_err >&2
    codegen_failures=1
  fi

  # task-message
  if datamodel-codegen --input "$CONTRACTS_DIR/schemas/rabbitmq/processing-task-message.schema.json" \
      --output "$TEMP_DIR/processing_task_message.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-task-msg"
    diff_path "$TEMP_DIR/processing_task_message.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/processing_task_message.py" \
      "Python processing_task_message.py"
  else
    fail "codegen:py-task-msg FAILED"
    cat $_err >&2
    codegen_failures=1
  fi
else
  warn "Python codegen skipped (datamodel-codegen not available)"
fi

# ── Final verdict ────────────────────────────────────────────────────────────
echo ""

if [ "$codegen_failures" -ne 0 ]; then
  echo "  Codegen failures: $codegen_failures target(s). Drift check incomplete."
  echo "  Fix codegen before re-running. ('npm run check' FAILED)"
  exit 1
fi

if [ "$drift_found" -ne 0 ]; then
  echo "  Codegen drift detected — committed files do not match contracts."
  echo "  Run 'npm run codegen' and commit the result."
  exit 1
fi

echo "  All generated files are in sync with contracts."
