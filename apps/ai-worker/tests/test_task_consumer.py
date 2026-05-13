from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.task_consumer import consume_and_validate


@pytest.fixture
def callback_client() -> MagicMock:
    client = MagicMock()
    client.fail_task = AsyncMock()
    return client


class TestConsumeAndValidate:
    @pytest.mark.asyncio
    async def test_invalid_message_calls_fail_task(self, callback_client: MagicMock) -> None:
        raw_message = {
            "taskId": "task_01",
            "tenantId": "tenant_01",
            "attemptNo": 2,
            "traceId": "trace_01",
        }

        with patch(
            "ai_worker.infrastructure.task_consumer.validate_and_parse_task_message",
            return_value=(None, ["schema error", "missing field"]),
        ):
            result = await consume_and_validate(raw_message, callback_client)

        assert result is None
        callback_client.fail_task.assert_awaited_once_with(
            task_id="task_01",
            tenant_id="tenant_01",
            attempt_no=2,
            failed_step="AUDIO_PREPROCESS",
            error_code="INVALID_TASK_MESSAGE",
            error_message="schema error; missing field",
            retryable=False,
            trace_id="trace_01",
        )

    @pytest.mark.asyncio
    async def test_invalid_text_embedding_uses_rag_indexing_as_failed_step(self, callback_client: MagicMock) -> None:
        raw_message = {
            "taskId": "task_text",
            "taskType": "TEXT_EMBEDDING",
            "tenantId": "tenant_01",
            "attemptNo": 1,
            "traceId": "trace_text",
        }

        with patch(
            "ai_worker.infrastructure.task_consumer.validate_and_parse_task_message",
            return_value=(None, ["missing documentId"]),
        ):
            result = await consume_and_validate(raw_message, callback_client)

        assert result is None
        callback_client.fail_task.assert_awaited_once_with(
            task_id="task_text",
            tenant_id="tenant_01",
            attempt_no=1,
            failed_step="RAG_INDEXING",
            error_code="INVALID_TASK_MESSAGE",
            error_message="missing documentId",
            retryable=False,
            trace_id="trace_text",
        )

    @pytest.mark.asyncio
    async def test_invalid_message_uses_defaults(self, callback_client: MagicMock) -> None:
        raw_message = {
            "taskId": "task_02",
            "tenantId": "tenant_02",
        }

        with patch(
            "ai_worker.infrastructure.task_consumer.validate_and_parse_task_message",
            return_value=(None, ["bad message"]),
        ):
            result = await consume_and_validate(raw_message, callback_client)

        assert result is None
        callback_client.fail_task.assert_awaited_once_with(
            task_id="task_02",
            tenant_id="tenant_02",
            attempt_no=1,
            failed_step="AUDIO_PREPROCESS",
            error_code="INVALID_TASK_MESSAGE",
            error_message="bad message",
            retryable=False,
            trace_id="fail-fast-task_02",
        )

    @pytest.mark.asyncio
    async def test_invalid_speaker_enrollment_uses_speaker_embedding_as_failed_step(self, callback_client: MagicMock) -> None:
        raw_message = {
            "taskId": "task_spk",
            "taskType": "SPEAKER_ENROLLMENT",
            "tenantId": "tenant_03",
        }

        with patch(
            "ai_worker.infrastructure.task_consumer.validate_and_parse_task_message",
            return_value=(None, ["missing speakerProfileId"]),
        ):
            result = await consume_and_validate(raw_message, callback_client)

        assert result is None
        callback_client.fail_task.assert_awaited_once_with(
            task_id="task_spk",
            tenant_id="tenant_03",
            attempt_no=1,
            failed_step="SPEAKER_EMBEDDING",
            error_code="INVALID_TASK_MESSAGE",
            error_message="missing speakerProfileId",
            retryable=False,
            trace_id="fail-fast-task_spk",
        )

    @pytest.mark.asyncio
    async def test_valid_message_returns_task_message(self, callback_client: MagicMock) -> None:
        task_msg = TaskMessage(
            task_id="task_03",
            task_type="MEETING_FULL_PIPELINE",
            tenant_id="tenant_03",
            security_level="INTERNAL",
            attempt_no=1,
            pipeline_steps=("AUDIO_PREPROCESS", "ASR"),
            trace_id="trace_03",
        )
        raw_message = {"taskId": "task_03"}

        with patch(
            "ai_worker.infrastructure.task_consumer.validate_and_parse_task_message",
            return_value=(task_msg, []),
        ):
            result = await consume_and_validate(raw_message, callback_client)

        assert result == task_msg
        callback_client.fail_task.assert_not_awaited()
