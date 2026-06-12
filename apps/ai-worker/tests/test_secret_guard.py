"""Phase J I7 — secret guard fail-closed tests."""

from __future__ import annotations

import pytest

from ai_worker.common.secret_guard import (
    SecretGuardError,
    assert_secrets_configured,
    check_secrets,
)


def test_dev_env_allows_dev_defaults(monkeypatch):
    """Dev environment accepts dev-default secrets."""
    monkeypatch.setenv("AI_WORKER_ENV", "dev")
    monkeypatch.setenv("AI_WORKER_CALLBACK_HMAC_SECRET", "dev-secret")
    monkeypatch.setenv("AI_WORKER_INTERNAL_API_HMAC_SECRET", "dev-internal-secret")
    monkeypatch.setenv("AI_WORKER_ADMIN_JWT_SECRET", "dev-admin-secret-32-bytes-fixedXX")
    # Should not raise
    from ai_worker.common.config import Settings

    settings = Settings()
    assert_secrets_configured(settings)


def test_production_rejects_dev_callback_hmac_secret(monkeypatch):
    """Production rejects dev-default callback HMAC secret."""
    monkeypatch.setenv("AI_WORKER_ENV", "production")
    monkeypatch.setenv("AI_WORKER_CALLBACK_HMAC_SECRET", "dev-secret")
    monkeypatch.setenv("AI_WORKER_INTERNAL_API_HMAC_SECRET", "real-secret-32-chars-or-longer-ok")
    monkeypatch.setenv("AI_WORKER_ADMIN_JWT_SECRET", "real-admin-secret-32-bytes-fixme")
    from ai_worker.common.config import Settings

    settings = Settings()
    with pytest.raises(SecretGuardError, match="AI_WORKER_CALLBACK_HMAC_SECRET"):
        assert_secrets_configured(settings)


def test_production_rejects_dev_internal_api_hmac_secret(monkeypatch):
    """Production rejects dev-default internal API HMAC secret."""
    monkeypatch.setenv("AI_WORKER_ENV", "production")
    monkeypatch.setenv("AI_WORKER_CALLBACK_HMAC_SECRET", "real-secret-32-chars-or-longer-ok")
    monkeypatch.setenv("AI_WORKER_INTERNAL_API_HMAC_SECRET", "dev-internal-secret")
    monkeypatch.setenv("AI_WORKER_ADMIN_JWT_SECRET", "real-admin-secret-32-bytes-fixme")
    from ai_worker.common.config import Settings

    settings = Settings()
    with pytest.raises(SecretGuardError, match="AI_WORKER_INTERNAL_API_HMAC_SECRET"):
        assert_secrets_configured(settings)


def test_production_rejects_dev_admin_jwt_secret(monkeypatch):
    """Production rejects dev-default admin JWT secret."""
    monkeypatch.setenv("AI_WORKER_ENV", "production")
    monkeypatch.setenv("AI_WORKER_CALLBACK_HMAC_SECRET", "real-secret-32-chars-or-longer-ok")
    monkeypatch.setenv("AI_WORKER_INTERNAL_API_HMAC_SECRET", "real-secret-32-chars-or-longer-ok")
    monkeypatch.setenv("AI_WORKER_ADMIN_JWT_SECRET", "dev-admin-secret-32-bytes-fixedXX")
    from ai_worker.common.config import Settings

    settings = Settings()
    with pytest.raises(SecretGuardError, match="AI_WORKER_ADMIN_JWT_SECRET"):
        assert_secrets_configured(settings)


def test_check_secrets_returns_violations():
    """check_secrets returns list of violations without raising."""
    from ai_worker.common.config import Settings

    # Create settings with dev defaults
    settings = Settings(
        env="production",
        callback_hmac_secret="dev-secret",
        internal_api_hmac_secret="dev-internal-secret",
        admin_jwt_secret="dev-admin-secret-32-bytes-fixedXX",
    )
    violations = check_secrets(settings)
    assert len(violations) == 3
    assert any("AI_WORKER_CALLBACK_HMAC_SECRET" in v for v in violations)
    assert any("AI_WORKER_INTERNAL_API_HMAC_SECRET" in v for v in violations)
    assert any("AI_WORKER_ADMIN_JWT_SECRET" in v for v in violations)
