"""Workstation BFF — admin UI backend.

Mounted under /admin/* in the FastAPI app. Validates a JWT minted by meeting-api
(HS256, shared secret), proxies requests to Java public API, and adds a small
in-process session store for voice-print enrollment (audio is staged in a tmp
dir for the preview→commit dance and never written to durable storage).
"""

from ai_worker.admin.router import build_admin_router  # noqa: F401
