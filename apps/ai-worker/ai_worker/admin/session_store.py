"""In-process enrollment session store.

Voice-print enrollment is a multi-step ritual:
  1. POST /admin/enrollment/sessions          → fresh session id
  2. PUT /admin/enrollment/sessions/{id}/audio → bytes staged in tmp dir
  3. POST /admin/enrollment/sessions/{id}/preview → returns quality_score
  4. POST /admin/enrollment/sessions/{id}/commit → creates Java enrollment

Between steps we keep a uuid → state record. The audio file is written to
``settings.enrollment_tmp_dir``; **never** to durable storage. TTL defaults
to 24h; a periodic cleanup task evicts expired sessions and removes their
files.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from ai_worker.common.config import settings

_log = logging.getLogger(__name__)


@dataclass
class EnrollmentSession:
    session_id: str
    tenant_id: str
    person_id: str | None
    created_at: float
    expires_at: float
    state: str = "CREATED"  # CREATED → AUDIO_UPLOADED → PREVIEWED → COMMITTED | ABORTED
    audio_path: Path | None = None
    quality_score: float | None = None
    embedding_preview: list[float] | None = None
    error: str | None = None
    artifacts: dict[str, str] = field(default_factory=dict)

    def is_expired(self, now: float | None = None) -> bool:
        current = time.time() if now is None else now
        return current >= self.expires_at

    def touch_audio(self, path: Path) -> None:
        self.audio_path = path
        self.state = "AUDIO_UPLOADED"

    def touch_preview(self, score: float, embedding: list[float]) -> None:
        self.quality_score = score
        self.embedding_preview = embedding
        self.state = "PREVIEWED"

    def touch_committed(self, artifact_ids: dict[str, str]) -> None:
        self.state = "COMMITTED"
        self.artifacts.update(artifact_ids)


class EnrollmentSessionStore:
    """Thread-safe in-process dict. Single-process only — sessions DO NOT survive restart."""

    def __init__(self, tmp_dir: str | None = None, ttl_seconds: int | None = None) -> None:
        self._tmp_dir = Path(tmp_dir or settings.enrollment_tmp_dir)
        self._ttl_seconds = ttl_seconds if ttl_seconds is not None else settings.admin_session_ttl_seconds
        self._sessions: dict[str, EnrollmentSession] = {}
        self._lock = asyncio.Lock()
        self._cleanup_task: Optional[asyncio.Task[None]] = None

    @property
    def tmp_dir(self) -> Path:
        return self._tmp_dir

    @property
    def ttl_seconds(self) -> int:
        return self._ttl_seconds

    def ensure_tmp_dir(self) -> None:
        self._tmp_dir.mkdir(parents=True, exist_ok=True)

    async def create(self, tenant_id: str, person_id: str | None) -> EnrollmentSession:
        async with self._lock:
            session_id = "enr_" + uuid.uuid4().hex
            now = time.time()
            session = EnrollmentSession(
                session_id=session_id,
                tenant_id=tenant_id,
                person_id=person_id,
                created_at=now,
                expires_at=now + self._ttl_seconds,
            )
            self._sessions[session_id] = session
            return session

    async def get(self, session_id: str) -> EnrollmentSession | None:
        async with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return None
            if session.is_expired():
                self._sessions.pop(session_id, None)
                self._cleanup_audio(session)
                return None
            return session

    async def replace(self, session: EnrollmentSession) -> None:
        async with self._lock:
            self._sessions[session.session_id] = session

    async def drop(self, session_id: str) -> None:
        async with self._lock:
            session = self._sessions.pop(session_id, None)
        if session is not None:
            self._cleanup_audio(session)

    async def evict_expired(self) -> int:
        async with self._lock:
            now = time.time()
            expired = [s for s in self._sessions.values() if s.is_expired(now)]
            for session in expired:
                self._sessions.pop(session.session_id, None)
        for session in expired:
            self._cleanup_audio(session)
        if expired:
            _log.info("enrollment_session_evicted count=%d", len(expired))
        return len(expired)

    def _cleanup_audio(self, session: EnrollmentSession) -> None:
        if session.audio_path is not None:
            try:
                if session.audio_path.exists():
                    session.audio_path.unlink()
            except OSError as exc:  # best effort — don't crash cleanup loop
                _log.warning(
                    "enrollment_audio_cleanup_failed session=%s path=%s reason=%s",
                    session.session_id, session.audio_path, exc,
                )

    async def _cleanup_loop(self, interval_seconds: int) -> None:
        try:
            while True:
                await asyncio.sleep(interval_seconds)
                try:
                    await self.evict_expired()
                except Exception:  # pragma: no cover — defensive
                    _log.exception("enrollment_session_cleanup_loop_failed")
        except asyncio.CancelledError:  # pragma: no cover — shutdown path
            return

    async def start_cleanup_loop(self, interval_seconds: int | None = None) -> None:
        if self._cleanup_task is not None and not self._cleanup_task.done():
            return
        self.ensure_tmp_dir()
        interval = interval_seconds if interval_seconds is not None else settings.admin_session_cleanup_interval_seconds
        self._cleanup_task = asyncio.create_task(self._cleanup_loop(interval))

    async def stop_cleanup_loop(self) -> None:
        if self._cleanup_task is None:
            return
        self._cleanup_task.cancel()
        try:
            await self._cleanup_task
        except asyncio.CancelledError:
            pass
        self._cleanup_task = None


enrollment_session_store = EnrollmentSessionStore()
