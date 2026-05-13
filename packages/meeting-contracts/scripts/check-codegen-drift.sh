#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Check that generated code is in sync with contracts.
# Run after `npm run codegen`; exits 0 if clean, exits 1 if drift detected.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")/../.."

drift_found=0

check_path() {
  local path="$1"
  local name="$2"
  git add -N "$path" 2>/dev/null || true
  if ! git diff --quiet -- "$path"; then
    echo "::error::$name is out of date or contains untracked files."
    echo "   Run 'npm run codegen' in packages/meeting-contracts and commit the result."
    drift_found=1
  fi
}

check_path "apps/meeting-web/src/shared/api/types.gen.ts"                 "TS types.gen.ts"
check_path "apps/ai-worker/ai_worker/generated/internal_callback_types.py" "Python internal_callback_types.py"
check_path "apps/ai-worker/ai_worker/generated/ai_worker_internal_types.py" "Python ai_worker_internal_types.py"
check_path "apps/ai-worker/ai_worker/generated/processing_task_message.py"  "Python processing_task_message.py"
check_path "apps/meeting-api/meeting-api-client/generated/public-api"       "Java public-api generated models"
check_path "apps/meeting-api/meeting-api-client/generated/ai-worker-internal" "Java ai-worker-internal generated models"

if [ "$drift_found" -ne 0 ]; then
  echo ""
  echo "Codegen drift detected. Please run 'npm run codegen' and commit the changes."
  exit 1
fi

echo "All generated files are in sync with contracts."
