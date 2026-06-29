from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timezone

import pytest

from ai_worker.infrastructure.internal_api.auth import verify_hmac_signature


class TestVerifyHmacSignature:
    def test_valid_signature_and_timestamp_returns_true(self) -> None:
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        nonce = "nonce_001"
        body = b'{"query":"test"}'
        method = "POST"
        path = "/internal/rerank"

        # Compute expected signature using the same secret as settings
        from ai_worker.common.config import settings

        signing_string = f"{now}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
        expected = hmac.new(
            settings.internal_api_hmac_secret.encode(),
            signing_string.encode(),
            hashlib.sha256,
        ).hexdigest()
        signature = f"hmac-sha256={expected}"

        assert verify_hmac_signature(method, path, body, now, nonce, signature) is True

    def test_invalid_signature_returns_false(self) -> None:
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        assert verify_hmac_signature("POST", "/internal/rerank", b"{}", now, "n1", "hmac-sha256=bad") is False

    def test_expired_timestamp_returns_false(self) -> None:
        old = "2020-01-01T00:00:00Z"
        from ai_worker.common.config import settings

        signing_string = f"{old}\nnonce\nPOST\n/internal/rerank\n{hashlib.sha256(b'{}').hexdigest()}"
        expected = hmac.new(
            settings.internal_api_hmac_secret.encode(),
            signing_string.encode(),
            hashlib.sha256,
        ).hexdigest()
        signature = f"hmac-sha256={expected}"

        assert verify_hmac_signature("POST", "/internal/rerank", b"{}", old, "nonce", signature) is False

    def test_replayed_nonce_returns_false(self) -> None:
        """Same nonce used twice must be rejected (replay protection)."""
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        nonce = "replay_nonce_001"
        body = b'{"query":"test"}'
        method = "POST"
        path = "/internal/rerank"

        from ai_worker.common.config import settings

        signing_string = f"{now}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
        expected = hmac.new(
            settings.internal_api_hmac_secret.encode(),
            signing_string.encode(),
            hashlib.sha256,
        ).hexdigest()
        signature = f"hmac-sha256={expected}"

        # First use succeeds
        assert verify_hmac_signature(method, path, body, now, nonce, signature) is True
        # Replay fails
        assert verify_hmac_signature(method, path, body, now, nonce, signature) is False

    def test_different_nonce_with_same_payload_succeeds(self) -> None:
        """Two requests with different nonces but same payload should both succeed."""
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        body = b'{"query":"test"}'
        method = "POST"
        path = "/internal/rerank"

        from ai_worker.common.config import settings

        for nonce in ["nonce_a", "nonce_b"]:
            signing_string = f"{now}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
            expected = hmac.new(
                settings.internal_api_hmac_secret.encode(),
                signing_string.encode(),
                hashlib.sha256,
            ).hexdigest()
            signature = f"hmac-sha256={expected}"
            assert verify_hmac_signature(method, path, body, now, nonce, signature) is True


class TestNonceCache:
    def test_nonce_evicted_after_ttl(self) -> None:
        from ai_worker.infrastructure.internal_api.auth import (
            _check_and_record_nonce,
            reset_nonce_cache,
        )

        reset_nonce_cache()
        assert _check_and_record_nonce("n1", 1000.0, 300) is False
        # Within TTL -> replay rejected.
        assert _check_and_record_nonce("n1", 1100.0, 300) is True
        # After TTL -> the stale entry is evicted and the nonce is accepted again.
        assert _check_and_record_nonce("n1", 1400.0, 300) is False

    def test_burst_does_not_evict_unexpired_nonce(self) -> None:
        # Regression for the old deque(maxlen=10_000): a flood of distinct
        # nonces inside the window must not push out a still-valid nonce and
        # reopen the replay surface.
        from ai_worker.infrastructure.internal_api.auth import (
            _check_and_record_nonce,
            reset_nonce_cache,
        )

        reset_nonce_cache()
        assert _check_and_record_nonce("victim", 1000.0, 300) is False
        for i in range(20_000):
            _check_and_record_nonce(f"flood_{i}", 1000.0, 300)
        # Still within the window, so the original nonce is still remembered.
        assert _check_and_record_nonce("victim", 1000.0, 300) is True


class TestSecurityConfigGuard:
    """validate_security_config must hard-fail on shipped-default secrets unless
    AI_WORKER_ALLOW_INSECURE_SECRETS is set."""

    def test_allows_when_insecure_secrets_permitted(self, monkeypatch) -> None:
        from ai_worker.common.config import settings, validate_security_config

        monkeypatch.setattr(settings, "allow_insecure_secrets", True)
        validate_security_config()  # must not raise

    def test_raises_on_default_secrets(self, monkeypatch) -> None:
        from ai_worker.common.config import (
            InsecureConfigError,
            settings,
            validate_security_config,
        )

        monkeypatch.setattr(settings, "allow_insecure_secrets", False)
        with pytest.raises(InsecureConfigError):
            validate_security_config(require_admin=False)

    def test_passes_with_strong_secrets(self, monkeypatch) -> None:
        from ai_worker.common.config import settings, validate_security_config

        monkeypatch.setattr(settings, "allow_insecure_secrets", False)
        monkeypatch.setattr(settings, "internal_api_hmac_secret", "a" * 40)
        monkeypatch.setattr(settings, "callback_hmac_secret", "b" * 40)
        validate_security_config(require_admin=False)  # must not raise

    def test_admin_secret_required_when_admin_enabled(self, monkeypatch) -> None:
        from ai_worker.common.config import (
            InsecureConfigError,
            Settings,
            settings,
            validate_security_config,
        )

        monkeypatch.setattr(settings, "allow_insecure_secrets", False)
        monkeypatch.setattr(settings, "internal_api_hmac_secret", "a" * 40)
        monkeypatch.setattr(settings, "callback_hmac_secret", "b" * 40)
        # Pin admin_jwt_secret to its shipped default (the admin test package
        # sets a strong override via env, so don't rely on ambient state).
        monkeypatch.setattr(
            settings, "admin_jwt_secret", Settings.model_fields["admin_jwt_secret"].default
        )
        with pytest.raises(InsecureConfigError, match="ADMIN_JWT_SECRET"):
            validate_security_config(require_admin=True)
