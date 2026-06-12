from __future__ import annotations

import asyncio

import httpx
import pytest

from ai_worker.infrastructure.speaker.reference_client import JavaSpeakerReferenceClient


@pytest.mark.asyncio
async def test_batch_retries_5xx_with_async_sleep_then_recovers(monkeypatch) -> None:
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        if attempts["n"] < 3:
            return httpx.Response(500)
        return httpx.Response(200, json={
            "success": True,
            "data": {"items": [
                {"personId": "p1", "speakerProfileId": "sp1", "values": [0.1, 0.2]},
            ]},
        })

    sleeps: list[float] = []

    async def fake_sleep(delay: float) -> None:
        sleeps.append(delay)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    client = JavaSpeakerReferenceClient(
        "http://java.test",
        "secret-not-default",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    result = await client.batch("tenant_01", ["p1"])

    assert attempts["n"] == 3
    assert sleeps == [0.2, 0.4]  # exponential backoff awaited, not time.sleep'd
    assert result["p1"].speaker_profile_id == "sp1"
    await client.close()
