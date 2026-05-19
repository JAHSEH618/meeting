"""Admin BFF test fixtures."""

import os

# Workstation tests need a JWT secret + Java base URL even though they mock
# the upstream client. Keep both deterministic.
os.environ.setdefault("AI_WORKER_ADMIN_JWT_SECRET", "test-admin-secret-32-bytes-fixedXX")
os.environ.setdefault("AI_WORKER_ADMIN_JWT_AUDIENCE", "ai-worker-admin")
os.environ.setdefault("AI_WORKER_ADMIN_JWT_ISSUER", "meeting-api")
os.environ.setdefault("AI_WORKER_JAVA_API_BASE_URL", "http://meeting-api.test")
