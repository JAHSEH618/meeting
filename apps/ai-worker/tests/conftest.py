"""Shared test configuration.

This module is imported by pytest before any test module, so the env vars
set here are visible to `ai_worker.common.config.settings` at first import.
"""

import os

import pytest

# Default to fake runtimes so the test suite never tries to download
# bge-m3 / bge-reranker weights. The `real_models` pytest marker is the
# explicit opt-in for tests that need real weights.
os.environ.setdefault("AI_WORKER_USE_FAKE_RUNTIME", "true")
# Tests run with the shipped default secrets; opt out of the production
# startup secret guard (validate_security_config). Set before any ai_worker
# import so the Settings singleton picks it up.
os.environ.setdefault("AI_WORKER_ALLOW_INSECURE_SECRETS", "true")


@pytest.fixture(autouse=True)
def _reset_checksum_cache():
    """The checksum cache is process-wide and keyed by path; clear it around
    every test so a restaged weight dir (or a reused path) can't return a
    stale memoized hash."""
    from ai_worker.observability.model_checksum import reset_checksum_cache

    reset_checksum_cache()
    yield
    reset_checksum_cache()
