"""C5.4 — JavaSpeakerReferenceClient unit tests with respx.

Covers:
* HMAC headers + signing-string format match Java verifier
* cache hit returns no second network call
* 401 raises SpeakerReferenceUnavailable
* plaintext values never enter logs (caplog assertion)
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging

import httpx
import pytest

from ai_worker.infrastructure.speaker.reference_client import (
    JavaSpeakerReferenceClient,
    SpeakerReferenceUnavailable,
    _sign,
)

SECRET = "test-internal-secret-with-32+-bytes-fixed"


def _envelope(items: list[dict]) -> dict:
    return {"success": True, "data": {"items": items}, "error": None, "requestId": "", "traceId": ""}


@pytest.fixture
def client():
    return JavaSpeakerReferenceClient(
        base_url="http://meeting-api.test",
        secret=SECRET,
        ttl_seconds=60,
    )


@pytest.mark.asyncio
async def test_signing_string_matches_java_verifier(client: JavaSpeakerReferenceClient):
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured["headers"] = dict(request.headers)
        captured["body"] = request.content
        captured["url"] = request.url.path
        return httpx.Response(200, json=_envelope([
            {"personId": "p_alice", "values": [0.6, 0.8], "dim": 2, "hash": "h", "computedAt": "2026-01-01T00:00:00Z"}
        ]))

    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        result = await client.batch("tenant_01", ["p_alice"])
    finally:
        await client._client.aclose()

    assert result["p_alice"] == [0.6, 0.8]
    # Reconstruct expected signature with the same fields the client sent
    headers = captured["headers"]
    body_hash = hashlib.sha256(captured["body"]).hexdigest()
    signing = f"{headers['x-timestamp']}\n{headers['x-nonce']}\nPOST\n/internal/speakers/reference-embeddings\n{body_hash}"
    expected = "hmac-sha256=" + hmac.new(SECRET.encode(), signing.encode(), hashlib.sha256).hexdigest()
    assert headers["x-signature"] == expected
    assert headers["x-tenant-id"] == "tenant_01"


@pytest.mark.asyncio
async def test_cache_hit_skips_second_network_call(client: JavaSpeakerReferenceClient):
    call_count = {"n": 0}

    async def handler(request: httpx.Request) -> httpx.Response:
        call_count["n"] += 1
        return httpx.Response(200, json=_envelope([
            {"personId": "p1", "values": [1.0, 0.0], "dim": 2, "hash": "h", "computedAt": "x"}
        ]))

    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        await client.batch("tenant_01", ["p1"])
        await client.batch("tenant_01", ["p1"])
    finally:
        await client._client.aclose()

    assert call_count["n"] == 1


@pytest.mark.asyncio
async def test_401_raises_speaker_reference_unavailable(client: JavaSpeakerReferenceClient):
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"success": False, "error": {"code": "CALLBACK_AUTH_FAILED", "message": "bad sig", "retryable": False}, "requestId": "", "traceId": ""})

    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(SpeakerReferenceUnavailable, match="401"):
            await client.batch("tenant_01", ["p1"])
    finally:
        await client._client.aclose()


@pytest.mark.asyncio
async def test_plaintext_values_never_logged(client: JavaSpeakerReferenceClient, caplog):
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_envelope([
            {"personId": "p1", "values": [0.123456789, 0.987654321], "dim": 2, "hash": "h", "computedAt": "x"}
        ]))

    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        caplog.set_level(logging.DEBUG, logger="ai_worker.infrastructure.speaker.reference_client")
        await client.batch("tenant_01", ["p1"])
    finally:
        await client._client.aclose()

    log_blob = "\n".join(r.getMessage() for r in caplog.records)
    assert "0.123456789" not in log_blob
    assert "0.987654321" not in log_blob


@pytest.mark.asyncio
async def test_5xx_retries_then_gives_up(client: JavaSpeakerReferenceClient, monkeypatch):
    call_count = {"n": 0}

    async def handler(request: httpx.Request) -> httpx.Response:
        call_count["n"] += 1
        return httpx.Response(503, json={"success": False, "error": {"code": "DEPENDENCY_UNAVAILABLE", "message": "", "retryable": True}, "requestId": "", "traceId": ""})

    # Patch sleep so the retry backoff isn't real seconds.
    import ai_worker.infrastructure.speaker.reference_client as mod
    monkeypatch.setattr(mod.time, "sleep", lambda _seconds: None)

    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(SpeakerReferenceUnavailable):
            await client.batch("tenant_01", ["p1"])
    finally:
        await client._client.aclose()
    assert call_count["n"] == 3  # _MAX_RETRIES


def test_sign_format_matches_spec():
    body = b'{"tenantId":"t","personIds":["p"]}'
    sig = _sign(SECRET, "POST", "/internal/speakers/reference-embeddings", body, "2026-05-19T06:00:00.000000Z", "n1")
    assert sig.startswith("hmac-sha256=")
    assert len(sig) == len("hmac-sha256=") + 64
