from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timezone


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
