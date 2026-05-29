"""Voice-print enrollment endpoints — 4-step session ritual + voiceprint admin.

The audio file lives only in the in-process session store's tmp dir.
``preview`` runs the embedding model locally without writing to Java.
``commit`` orchestrates the three Java public-API calls that finally make
the enrollment durable.

In tests we replace the embedding callable with a deterministic stub so the
heavy 3D-Speaker / bge-m3 weights stay out of the path.
"""

from __future__ import annotations

import hashlib
import httpx
import logging
import math
from pathlib import Path
from typing import Any, Awaitable, Callable

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import error, ok, passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency
from ai_worker.admin.session_store import EnrollmentSession, EnrollmentSessionStore, enrollment_session_store

_log = logging.getLogger(__name__)


PreviewFn = Callable[[Path, EnrollmentSession], Awaitable[dict[str, Any]]]


async def _default_preview(audio_path: Path, session: EnrollmentSession) -> dict[str, Any]:
    """Deterministic dev/test embedding: produces a 16-dim unit vector +
    a quality_score in [0, 1] derived from the file size. Real production
    replaces this with the 3D-Speaker runtime; that wiring is part of P5.
    """
    raw = audio_path.read_bytes()
    if not raw:
        return {"quality_score": 0.0, "embedding": [0.0] * 16, "duration_ms": 0}
    seed = sum(raw[:512])
    vector = [math.sin((seed + i) / 7.0) for i in range(16)]
    norm = math.sqrt(sum(v * v for v in vector)) or 1.0
    embedding = [v / norm for v in vector]
    quality_score = min(1.0, len(raw) / 32_000.0)
    return {
        "quality_score": round(quality_score, 4),
        "embedding": embedding,
        "duration_ms": len(raw),
    }


def build_enrollment_router(
    *,
    java_client: JavaPublicClient,
    session_store: EnrollmentSessionStore = enrollment_session_store,
    preview_fn: PreviewFn = _default_preview,
) -> APIRouter:
    router = APIRouter(prefix="/admin/enrollment", tags=["admin-enrollment"])

    @router.post("/sessions", status_code=200)
    async def create_session(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        body = await request.json() if (await request.body()) else {}
        person_id = body.get("personId") if isinstance(body, dict) else None
        session = await session_store.create(claims.tenant_id, person_id)
        return ok({"sessionId": session.session_id, "personId": person_id, "state": session.state}, x_request_id, x_trace_id)

    @router.put("/sessions/{session_id}/audio", status_code=200)
    async def upload_audio(
        session_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        session = await session_store.get(session_id)
        if session is None or session.tenant_id != claims.tenant_id:
            return error(status_code=404, code="ENROLLMENT_SESSION_NOT_FOUND",
                         message="session not found or expired", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)
        session_store.ensure_tmp_dir()
        audio_path = session_store.tmp_dir / f"{session_id}.bin"
        audio_path.write_bytes(await request.body())
        session.touch_audio(audio_path)
        await session_store.replace(session)
        return ok({"sessionId": session_id, "state": session.state, "sizeBytes": audio_path.stat().st_size},
                  x_request_id, x_trace_id)

    @router.post("/sessions/{session_id}/preview", status_code=200)
    async def preview(
        session_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        session = await session_store.get(session_id)
        if session is None or session.tenant_id != claims.tenant_id:
            return error(status_code=404, code="ENROLLMENT_SESSION_NOT_FOUND",
                         message="session not found or expired", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)
        if session.audio_path is None or not session.audio_path.exists():
            return error(status_code=409, code="ENROLLMENT_AUDIO_MISSING",
                         message="upload audio before preview", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)
        result = await preview_fn(session.audio_path, session)
        score = float(result.get("quality_score", 0.0))
        embedding = list(result.get("embedding", []))
        session.touch_preview(score, embedding)
        await session_store.replace(session)
        return ok({
            "sessionId": session_id,
            "state": session.state,
            "qualityScore": session.quality_score,
            "durationMs": result.get("duration_ms"),
        }, x_request_id, x_trace_id)

    @router.post("/sessions/{session_id}/commit", status_code=200)
    async def commit(
        session_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        session = await session_store.get(session_id)
        if session is None or session.tenant_id != claims.tenant_id:
            return error(status_code=404, code="ENROLLMENT_SESSION_NOT_FOUND",
                         message="session not found or expired", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)
        if session.state != "PREVIEWED":
            return error(status_code=409, code="ENROLLMENT_NOT_PREVIEWED",
                         message=f"session must be PREVIEWED before commit (state={session.state})",
                         retryable=False, request_id=x_request_id, trace_id=x_trace_id)
        if session.audio_path is None or not session.audio_path.exists():
            return error(status_code=409, code="ENROLLMENT_AUDIO_MISSING",
                         message="audio file missing before commit", retryable=False,
                         request_id=x_request_id, trace_id=x_trace_id)

        # Three-step orchestration: profile → generic file upload → enrollment record.
        # Each Java call is independently idempotent via its own Idempotency-Key.
        profile = await java_client.request(
            "POST", "/api/speaker-profiles",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=f"{idempotency_key or session_id}:profile",
            json={
                "personId": session.person_id,
                "displayName": session.person_id,
                "consentSource": "workstation",
                "consentVersion": "v1",
            },
        )
        if profile.status_code >= 400:
            return passthrough(profile.status_code, profile.content, x_request_id, x_trace_id)
        profile_body = profile.json()
        profile_id = (
            (profile_body.get("data") or {}).get("profileId")
            or (profile_body.get("data") or {}).get("speakerProfileId")
        )
        if not profile_id:
            return error(
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
                message="speaker profile response missing profileId",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        audio_bytes = session.audio_path.read_bytes()
        file_sha = hashlib.sha256(audio_bytes).hexdigest()
        init = await java_client.request(
            "POST", "/api/files",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=f"{idempotency_key or session_id}:init",
            json={
                "fileName": f"enroll-{session_id}.wav",
                "contentType": "audio/wav",
                "fileSizeBytes": len(audio_bytes),
                "fileSha256": file_sha,
            },
        )
        if init.status_code >= 400:
            return passthrough(init.status_code, init.content, x_request_id, x_trace_id)
        init_body = init.json()
        upload_id = (init_body.get("data") or {}).get("uploadId")
        parts = (init_body.get("data") or {}).get("parts") or []
        if not upload_id or not parts:
            return error(
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
                message="file upload response missing uploadId or parts",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )
        upload_url = parts[0].get("presignedUrl") or parts[0].get("uploadUrl")
        if not upload_url:
            return error(
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
                message="file upload response missing signed URL",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        async with httpx.AsyncClient(timeout=60) as client:
            put_response = await client.put(
                upload_url,
                content=audio_bytes,
                headers={"Content-Type": "audio/wav"},
            )
        if put_response.status_code >= 400:
            return error(
                status_code=502,
                code="DEPENDENCY_UNAVAILABLE",
                message="failed to upload enrollment audio to signed URL",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )
        etag = (put_response.headers.get("etag") or "").strip('"')
        complete = await java_client.request(
            "POST", f"/api/files/{upload_id}/complete",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=f"{idempotency_key or session_id}:complete",
            json={
                "fileSha256": file_sha,
                "parts": [{"partNumber": 1, "partSha256": file_sha, "etag": etag}],
            },
        )
        if complete.status_code >= 400:
            return passthrough(complete.status_code, complete.content, x_request_id, x_trace_id)
        complete_body = complete.json()
        file_id = (complete_body.get("data") or {}).get("fileId")
        if not file_id:
            return error(
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
                message="file complete response missing fileId",
                retryable=True,
                request_id=x_request_id,
                trace_id=x_trace_id,
            )

        enrollment = await java_client.request(
            "POST", f"/api/speaker-profiles/{profile_id}/enrollments",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=f"{idempotency_key or session_id}:enroll",
            json={"sourceAudioFileId": file_id},
        )
        if enrollment.status_code >= 400:
            return passthrough(enrollment.status_code, enrollment.content, x_request_id, x_trace_id)

        artifacts = {
            "profileResponse": profile.text,
            "completeResponse": complete.text,
            "enrollmentResponse": enrollment.text,
        }
        session.touch_committed(artifacts)
        await session_store.replace(session)
        # Drop audio file; we've handed off to Java's durable store.
        await session_store.drop(session_id)
        return ok(
            {
                "sessionId": session_id,
                "state": "COMMITTED",
                "profileId": profile_id,
                "fileId": file_id,
            },
            x_request_id,
            x_trace_id,
        )

    return router


def build_voiceprint_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin/voiceprints", tags=["admin-voiceprints"])

    @router.get("", status_code=200)
    async def list_voiceprints(
        person_id: str | None = None,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        params: dict[str, Any] = {}
        if person_id:
            params["personId"] = person_id
        response = await java_client.request(
            "GET", "/api/speakers/profiles",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            params=params,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    @router.post("/{enrollment_id}:revoke", status_code=200)
    async def revoke(
        enrollment_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        response = await java_client.request(
            "POST", f"/api/speakers/enrollments/{enrollment_id}:revoke",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(response.status_code, response.content, x_request_id, x_trace_id)

    return router
