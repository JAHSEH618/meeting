"""Shared test configuration.

This module is imported by pytest before any test module, so the env vars
set here are visible to `ai_worker.common.config.settings` at first import.
"""

import os

# Default to fake runtimes so the test suite never tries to download
# bge-m3 / bge-reranker weights. The `real_models` pytest marker is the
# explicit opt-in for tests that need real weights.
os.environ.setdefault("AI_WORKER_USE_FAKE_RUNTIME", "true")
