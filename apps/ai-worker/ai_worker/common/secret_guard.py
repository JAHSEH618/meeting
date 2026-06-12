"""Phase J I7 — fail closed on dev-default secrets outside dev environment.

Production / staging deployments must override all three HMAC/JWT secrets:
  - AI_WORKER_CALLBACK_HMAC_SECRET
  - AI_WORKER_INTERNAL_API_HMAC_SECRET
  - AI_WORKER_ADMIN_JWT_SECRET

Dev environment (AI_WORKER_ENV=dev) allows the dev defaults; all other values
(production / staging / test / <anything-else>) reject them with a fatal error
at startup.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ai_worker.common.config import Settings


class SecretGuardError(RuntimeError):
    """Raised at startup when a dev-default secret is used outside dev."""


_DEV_DEFAULTS = {
    "callback_hmac_secret": "dev-secret",
    "internal_api_hmac_secret": "dev-internal-secret",
    "admin_jwt_secret": "dev-admin-secret-32-bytes-fixedXX",
}


def check_secrets(settings: Settings) -> list[str]:
    """Return list of violations without raising.

    Useful for structured reporting (e.g. /internal/ready endpoint) where
    we want to return all violations at once rather than failing on the
    first mismatch.
    """
    if settings.env == "dev":
        return []

    violations: list[str] = []

    if settings.callback_hmac_secret == _DEV_DEFAULTS["callback_hmac_secret"]:
        violations.append(
            "AI_WORKER_CALLBACK_HMAC_SECRET is set to the dev-default value "
            "but env is not 'dev'"
        )

    if settings.internal_api_hmac_secret == _DEV_DEFAULTS["internal_api_hmac_secret"]:
        violations.append(
            "AI_WORKER_INTERNAL_API_HMAC_SECRET is set to the dev-default value "
            "but env is not 'dev'"
        )

    if settings.admin_jwt_secret == _DEV_DEFAULTS["admin_jwt_secret"]:
        violations.append(
            "AI_WORKER_ADMIN_JWT_SECRET is set to the dev-default value "
            "but env is not 'dev'"
        )

    return violations


def assert_secrets_configured(settings: Settings) -> None:
    """Fail-fast guard called at startup (main.create_app, rabbitmq.run).

    Raises SecretGuardError if any dev-default secret is in use outside
    the dev environment.
    """
    violations = check_secrets(settings)
    if violations:
        raise SecretGuardError(
            f"Secret configuration error (env={settings.env}): "
            + "; ".join(violations)
        )
