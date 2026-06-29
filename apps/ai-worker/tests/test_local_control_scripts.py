from __future__ import annotations

import os
from pathlib import Path


APP_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPTS_DIR = REPO_ROOT / "scripts"


# Root-level, no-argument entrypoints (repo-root scripts/). Each delegates to the
# shared apps/ai-worker/scripts/local-control.sh engine with fixed args.
EXPECTED_SCRIPTS = {
    "ai-worker-start.sh": 'local-control.sh" start api local',
    "ai-worker-stop.sh": 'local-control.sh" stop api',
    "ai-worker-restart.sh": 'local-control.sh" restart api local',
    "ai-worker-web-start.sh": 'local-control.sh" start web local',
    "ai-worker-web-stop.sh": 'local-control.sh" stop web',
    "ai-worker-web-restart.sh": 'local-control.sh" restart web local',
    "ai-worker-centos-start.sh": 'local-control.sh" start api centos',
    "ai-worker-centos-stop.sh": 'local-control.sh" stop api',
    "ai-worker-centos-restart.sh": 'local-control.sh" restart api centos',
    "ai-worker-web-centos-start.sh": 'local-control.sh" start web centos',
    "ai-worker-web-centos-stop.sh": 'local-control.sh" stop web',
    "ai-worker-web-centos-restart.sh": 'local-control.sh" restart web centos',
}


# Wrappers that used to live under apps/ai-worker/. They were consolidated into
# the repo-root scripts/ directory and must not come back here.
REMOVED_APP_WRAPPERS = tuple(
    f"{service}{env}-{action}.sh"
    for service in ("api", "web", "all")
    for env in ("", "-centos")
    for action in ("start", "stop", "restart")
) + ("start.sh", "stop.sh", "restart.sh")


def test_root_lifecycle_scripts_are_named_no_arg_entrypoints() -> None:
    for script_name, expected_exec in EXPECTED_SCRIPTS.items():
        script_path = SCRIPTS_DIR / script_name

        assert script_path.exists(), f"missing scripts/{script_name}"
        assert os.access(script_path, os.X_OK), f"scripts/{script_name} is not executable"
        script_text = script_path.read_text(encoding="utf-8")
        # Rejects extra tail arguments (no-parameter contract).
        assert 'if [ "$#" -ne 0 ]; then' in script_text
        # Service and environment are fixed in the script name, never forwarded.
        assert '"$@"' not in script_text
        assert expected_exec in script_text


def test_deprecated_app_level_wrappers_are_removed() -> None:
    for script_name in REMOVED_APP_WRAPPERS:
        assert not (APP_DIR / script_name).exists(), (
            f"remove deprecated apps/ai-worker/{script_name}; use scripts/ instead"
        )
