#!/usr/bin/env bash
# One-shot installer for the repo's git hooks (final-check.md G4).
#
# Run once after cloning:
#   bash scripts/install-git-hooks.sh
#
# Re-running is safe — it just rewrites the symlink target.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK_DIR="$REPO_ROOT/.git-hooks"

if [[ ! -d "$HOOK_DIR" ]]; then
    echo "✗ $HOOK_DIR not found — run from a fresh clone" >&2
    exit 1
fi

chmod +x "$HOOK_DIR"/*
git -C "$REPO_ROOT" config core.hooksPath ".git-hooks"
echo "✓ core.hooksPath set to .git-hooks"
echo "  pre-commit gates: gitleaks (when installed) + yaml/bash syntax on staged files"
echo "  bypass once with: git commit --no-verify"
