from __future__ import annotations

import os
from pathlib import Path


APP_DIR = Path(__file__).resolve().parents[1]


EXPECTED_SCRIPTS = {
    "api-start.sh": "exec ./scripts/local-control.sh start api local",
    "api-stop.sh": "exec ./scripts/local-control.sh stop api",
    "api-restart.sh": "exec ./scripts/local-control.sh restart api local",
    "web-start.sh": "exec ./scripts/local-control.sh start web local",
    "web-stop.sh": "exec ./scripts/local-control.sh stop web",
    "web-restart.sh": "exec ./scripts/local-control.sh restart web local",
    "all-start.sh": "exec ./scripts/local-control.sh start all local",
    "all-stop.sh": "exec ./scripts/local-control.sh stop all",
    "all-restart.sh": "exec ./scripts/local-control.sh restart all local",
    "api-centos-start.sh": "exec ./scripts/local-control.sh start api centos",
    "api-centos-stop.sh": "exec ./scripts/local-control.sh stop api",
    "api-centos-restart.sh": "exec ./scripts/local-control.sh restart api centos",
    "web-centos-start.sh": "exec ./scripts/local-control.sh start web centos",
    "web-centos-stop.sh": "exec ./scripts/local-control.sh stop web",
    "web-centos-restart.sh": "exec ./scripts/local-control.sh restart web centos",
    "all-centos-start.sh": "exec ./scripts/local-control.sh start all centos",
    "all-centos-stop.sh": "exec ./scripts/local-control.sh stop all",
    "all-centos-restart.sh": "exec ./scripts/local-control.sh restart all centos",
}


def test_component_lifecycle_scripts_are_named_entrypoints() -> None:
    for script_name, expected_exec in EXPECTED_SCRIPTS.items():
        script_path = APP_DIR / script_name

        assert script_path.exists(), f"missing {script_name}"
        assert os.access(script_path, os.X_OK), f"{script_name} is not executable"
        script_text = script_path.read_text(encoding="utf-8")
        assert 'if [ "$#" -ne 0 ]; then' in script_text
        assert '"$@"' not in script_text
        assert expected_exec in script_text


def test_deprecated_argument_based_lifecycle_scripts_are_removed() -> None:
    for script_name in ("start.sh", "stop.sh", "restart.sh"):
        assert not (APP_DIR / script_name).exists(), f"remove deprecated {script_name}"
