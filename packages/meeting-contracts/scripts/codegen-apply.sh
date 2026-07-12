#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Apply codegen: regenerate to a temp dir, then copy to committed locations.
# Safe even when target directories are read-only — copy is best-effort.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$CONTRACTS_DIR/../.." && pwd)"
TEMP_DIR="$CONTRACTS_DIR/.generated-apply"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; }

failures=0
copy_failures=0
OPENAPI_TS_BIN="$CONTRACTS_DIR/node_modules/.bin/openapi-typescript"
OPENAPI_GENERATOR_BIN="$CONTRACTS_DIR/node_modules/.bin/openapi-generator-cli"

# ── Preflight ────────────────────────────────────────────────────────────────
if [ ! -x "$OPENAPI_TS_BIN" ]; then
  echo "  FAIL: local openapi-typescript not found. Run 'npm ci' in $CONTRACTS_DIR."
  exit 1
fi
if [ ! -x "$OPENAPI_GENERATOR_BIN" ]; then
  echo "  FAIL: local openapi-generator-cli not found. Run 'npm ci' in $CONTRACTS_DIR."
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "  FAIL: Java 17 is required for Java codegen."
  exit 1
fi
java_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
case "$java_version" in
  17.*) ;;
  *)
    echo "  FAIL: Java 17 is required for Java codegen; current java.version is $java_version."
    echo "  Set JAVA_HOME to a JDK 17 installation before running contract codegen."
    exit 1
    ;;
esac

cd "$CONTRACTS_DIR"

rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

_err=$(mktemp)
trap "rm -f $_err; rm -rf $TEMP_DIR" EXIT

echo "  Regenerating to $TEMP_DIR ..."

# ── Helpers ──────────────────────────────────────────────────────────────────
copy_tree() {
  local src="$1"
  local dst="$2"
  local label="$3"
  if [ -d "$src" ]; then
    if cp -R "$src"/* "$dst/" 2>/dev/null; then
      pass "$label updated"
    else
      warn "$label: copy failed (EPERM? target may be read-only)"
      copy_failures=1
    fi
  fi
}

copy_file() {
  local src="$1"
  local dst="$2"
  local label="$3"
  if [ -f "$src" ]; then
    if cp "$src" "$dst" 2>/dev/null; then
      pass "$label updated"
    else
      warn "$label: copy failed (EPERM? target may be read-only)"
      copy_failures=1
    fi
  fi
}

# ── TS ───────────────────────────────────────────────────────────────────────
# Both frontends consume the same public-api.yaml; generate once, copy twice
# so neither can silently drift from the contract.
if "$OPENAPI_TS_BIN" "$CONTRACTS_DIR/openapi/public-api.yaml" \
    -o "$TEMP_DIR/types.gen.ts" >/dev/null 2>$_err; then
  pass "codegen:ts"
  copy_file "$TEMP_DIR/types.gen.ts" \
    "$PROJECT_ROOT/apps/meeting-web/src/shared/api/types.gen.ts" \
    "TS types.gen.ts (meeting-web)"
  copy_file "$TEMP_DIR/types.gen.ts" \
    "$PROJECT_ROOT/apps/ai-worker-web/src/shared/api/types.gen.ts" \
    "TS types.gen.ts (ai-worker-web)"
else
  fail "codegen:ts FAILED"
  cat $_err >&2
  failures=1
fi

# ── TS error messages (error-codes.yaml → both frontends) ───────────────────
if python3 "$SCRIPT_DIR/generate-error-messages.py" \
    --output "$TEMP_DIR/error-messages.gen.ts" >/dev/null 2>$_err; then
  pass "codegen:error-messages"
  copy_file "$TEMP_DIR/error-messages.gen.ts" \
    "$PROJECT_ROOT/apps/meeting-web/src/shared/api/error-messages.gen.ts" \
    "TS error-messages.gen.ts (meeting-web)"
  copy_file "$TEMP_DIR/error-messages.gen.ts" \
    "$PROJECT_ROOT/apps/ai-worker-web/src/shared/api/error-messages.gen.ts" \
    "TS error-messages.gen.ts (ai-worker-web)"
else
  fail "codegen:error-messages FAILED"
  cat $_err >&2
  failures=1
fi

# ── Java: public-api ─────────────────────────────────────────────────────────
JAVA_PUB_TEMP="$TEMP_DIR/java-public"
if "$OPENAPI_GENERATOR_BIN" generate -g java \
    -i "$CONTRACTS_DIR/openapi/public-api.yaml" \
    -o "$JAVA_PUB_TEMP" \
    --additional-properties=apiPackage=com.meeting.api.client.publicapi,modelPackage=com.meeting.api.client.publicapi.model,hideGenerationTimestamp=true \
    >/dev/null 2>$_err; then
  bash "$SCRIPT_DIR/cleanup-java-codegen.sh" "$JAVA_PUB_TEMP" >/dev/null 2>&1 || true
  pass "codegen:java-public"
  copy_tree "$JAVA_PUB_TEMP" \
    "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/public-api" \
    "Java public-api"
else
  fail "codegen:java-public FAILED"
  cat $_err >&2
  failures=1
fi

# ── Java: ai-worker-internal ─────────────────────────────────────────────────
JAVA_WK_TEMP="$TEMP_DIR/java-worker-internal"
if "$OPENAPI_GENERATOR_BIN" generate -g java \
    -i "$CONTRACTS_DIR/openapi/ai-worker-internal-api.yaml" \
    -o "$JAVA_WK_TEMP" \
    --additional-properties=apiPackage=com.meeting.api.client.workerinternal,modelPackage=com.meeting.api.client.workerinternal.model,hideGenerationTimestamp=true \
    >/dev/null 2>$_err; then
  bash "$SCRIPT_DIR/cleanup-java-codegen.sh" "$JAVA_WK_TEMP" >/dev/null 2>&1 || true
  pass "codegen:java-worker-internal"
  copy_tree "$JAVA_WK_TEMP" \
    "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/ai-worker-internal" \
    "Java ai-worker-internal"
else
  fail "codegen:java-worker-internal FAILED"
  cat $_err >&2
  failures=1
fi

# ── Java: export-job ─────────────────────────────────────────────────────────
if [ -f "$CONTRACTS_DIR/openapi/export-job-message.yaml" ]; then
  JAVA_EXP_TEMP="$TEMP_DIR/java-export-job"
  if "$OPENAPI_GENERATOR_BIN" generate -g java \
      -i "$CONTRACTS_DIR/openapi/export-job-message.yaml" \
      -o "$JAVA_EXP_TEMP" \
      --additional-properties=apiPackage=com.meeting.api.client.exportjob,modelPackage=com.meeting.api.client.exportjob.model,hideGenerationTimestamp=true \
      >/dev/null 2>$_err; then
    bash "$SCRIPT_DIR/cleanup-java-codegen.sh" "$JAVA_EXP_TEMP" >/dev/null 2>&1 || true
    pass "codegen:java-export-job"
    copy_tree "$JAVA_EXP_TEMP" \
      "$PROJECT_ROOT/apps/meeting-api/meeting-api-client/generated/export-job" \
      "Java export-job"
  else
    fail "codegen:java-export-job FAILED"
    cat $_err >&2
    failures=1
  fi
fi

# ── Python ───────────────────────────────────────────────────────────────────
if command -v datamodel-codegen &>/dev/null || python3 -c "import datamodel_code_generator" 2>/dev/null; then
  if datamodel-codegen --input "$CONTRACTS_DIR/openapi/internal-callback-api.yaml" \
      --input-file-type openapi --output "$TEMP_DIR/internal_callback_types.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-callback"
    copy_file "$TEMP_DIR/internal_callback_types.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/internal_callback_types.py" \
      "Python internal_callback_types.py"
  else
    fail "codegen:py-callback FAILED"
    cat $_err >&2
    failures=1
  fi

  if datamodel-codegen --input "$CONTRACTS_DIR/openapi/ai-worker-internal-api.yaml" \
      --input-file-type openapi --output "$TEMP_DIR/ai_worker_internal_types.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-worker-internal"
    copy_file "$TEMP_DIR/ai_worker_internal_types.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/ai_worker_internal_types.py" \
      "Python ai_worker_internal_types.py"
  else
    fail "codegen:py-worker-internal FAILED"
    cat $_err >&2
    failures=1
  fi

  if datamodel-codegen --input "$CONTRACTS_DIR/schemas/rabbitmq/processing-task-message.schema.json" \
      --output "$TEMP_DIR/processing_task_message.py" \
      --disable-timestamp >/dev/null 2>$_err; then
    pass "codegen:py-task-msg"
    copy_file "$TEMP_DIR/processing_task_message.py" \
      "$PROJECT_ROOT/apps/ai-worker/ai_worker/generated/processing_task_message.py" \
      "Python processing_task_message.py"
  else
    fail "codegen:py-task-msg FAILED"
    cat $_err >&2
    failures=1
  fi
else
  warn "Python codegen skipped (datamodel-codegen not available)"
fi

echo ""

if [ "$failures" -ne 0 ]; then
  echo "  Codegen FAILED ($failures target(s))."
  exit 1
fi

if [ "$copy_failures" -ne 0 ]; then
  echo "  Codegen succeeded, but some target files could not be overwritten (EPERM)."
  echo "  Generated output is in $TEMP_DIR — copy manually or fix permissions."
  exit 1
fi

echo "  All codegen targets regenerated successfully."
