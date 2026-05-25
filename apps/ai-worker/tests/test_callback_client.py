from __future__ import annotations

import hashlib
import hmac
from unittest.mock import patch

import pytest

from ai_worker.infrastructure.java_callback.client import CallbackResponse, JavaCallbackClient


@pytest.fixture
def client() -> JavaCallbackClient:
    return JavaCallbackClient(base_url="http://localhost:8080")


class TestSign:
    def test_sign_is_deterministic_for_same_inputs(self, client: JavaCallbackClient) -> None:
        result1 = client._sign("PATCH", "/internal/test", '{"a":1}', "2026-01-01T00:00:00Z", "nonce1")
        result2 = client._sign("PATCH", "/internal/test", '{"a":1}', "2026-01-01T00:00:00Z", "nonce1")
        assert result1 == result2
        assert result1.startswith("hmac-sha256=")

    def test_sign_differs_for_different_inputs(self, client: JavaCallbackClient) -> None:
        sig_a = client._sign("PATCH", "/internal/test", '{"a":1}', "2026-01-01T00:00:00Z", "nonce1")
        sig_b = client._sign("PATCH", "/internal/test", '{"a":2}', "2026-01-01T00:00:00Z", "nonce1")
        sig_c = client._sign("POST", "/internal/other", '{"a":1}', "2026-01-01T00:00:00Z", "nonce1")
        assert sig_a != sig_b
        assert sig_a != sig_c

    def test_sign_matches_manual_computation(self, client: JavaCallbackClient) -> None:
        method = "PATCH"
        path = "/internal/processing-tasks/t1/steps/ASR"
        body = '{"taskId":"t1"}'
        timestamp = "2026-01-01T00:00:00Z"
        nonce = "fix_nonce"
        signing_string = f"{timestamp}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body.encode()).hexdigest()}"
        expected = hmac.new(client.hmac_secret, signing_string.encode(), hashlib.sha256).hexdigest()
        result = client._sign(method, path, body, timestamp, nonce)
        assert result == f"hmac-sha256={expected}"


class TestBuildHeaders:
    def test_all_required_hmac_headers_present(self, client: JavaCallbackClient) -> None:
        with patch.object(client, "_generate_nonce", return_value="test_nonce"):
            headers = client._build_headers(
                method="PATCH",
                path="/internal/test",
                body='{"key":"val"}',
                task_id="task_001",
                attempt_no=1,
                trace_id="trace_001",
                idempotency_key="idem_001",
            )

        required_headers = [
            "X-Worker-Id",
            "X-Attempt-No",
            "X-Lease-Owner",
            "X-Timestamp",
            "X-Nonce",
            "X-Signature",
            "X-Trace-Id",
            "X-Request-Id",
            "Idempotency-Key",
        ]
        for h in required_headers:
            assert h in headers, f"Missing header: {h}"

    def test_header_values_are_correct(self, client: JavaCallbackClient) -> None:
        with patch.object(client, "_generate_nonce", return_value="fixed_nonce"):
            headers = client._build_headers(
                method="PATCH",
                path="/internal/test",
                body='{}',
                task_id="task_001",
                attempt_no=2,
                trace_id="trace_abc",
                idempotency_key="idem_key",
            )

        assert headers["X-Worker-Id"] == client.worker_id
        assert headers["X-Attempt-No"] == "2"
        assert headers["X-Lease-Owner"] == f"{client.worker_id}:task_001:2"
        assert headers["X-Nonce"] == "fixed_nonce"
        assert headers["X-Trace-Id"] == "trace_abc"
        assert headers["X-Request-Id"] == "trace_abc:task_001"
        assert headers["Idempotency-Key"] == "idem_key"
        assert headers["Content-Type"] == "application/json"

    def test_signature_is_computed(self, client: JavaCallbackClient) -> None:
        with patch.object(client, "_generate_nonce", return_value="fixed_nonce"):
            headers = client._build_headers(
                method="PATCH",
                path="/internal/test",
                body='{}',
                task_id="task_001",
                attempt_no=1,
                trace_id="t1",
                idempotency_key="ik1",
            )

        assert headers["X-Signature"].startswith("hmac-sha256=")
        assert len(headers["X-Signature"]) > len("hmac-sha256=")


class TestUpdateStep:
    @pytest.mark.asyncio
    async def test_request_body_contains_correct_fields(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            result = await client.update_step(
                task_id="task_42",
                tenant_id="tenant_acme",
                step_name="ASR",
                attempt_no=1,
                status="RUNNING",
                progress=50,
            )

        assert captured_body["tenantId"] == "tenant_acme"
        assert captured_body["taskId"] == "task_42"
        assert captured_body["stepName"] == "ASR"
        assert captured_body["status"] == "RUNNING"
        assert captured_body["progress"] == 50
        assert captured_body["attemptNo"] == 1
        assert result.accepted is True

    @pytest.mark.asyncio
    async def test_tenant_id_is_passed_through(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            await client.update_step(
                task_id="t1",
                tenant_id="my_custom_tenant",
                step_name="DIARIZATION",
                attempt_no=3,
                status="SUCCEEDED",
                progress=100,
            )

        assert captured_body["tenantId"] == "my_custom_tenant"

    @pytest.mark.asyncio
    async def test_error_code_included_when_provided(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            await client.update_step(
                task_id="t1",
                tenant_id="ten1",
                step_name="ASR",
                attempt_no=1,
                status="FAILED",
                progress=0,
                error_code="GPU_OOM",
            )

        assert captured_body["errorCode"] == "GPU_OOM"

    @pytest.mark.asyncio
    async def test_error_code_absent_when_not_provided(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            await client.update_step(
                task_id="t1",
                tenant_id="ten1",
                step_name="ASR",
                attempt_no=1,
                status="RUNNING",
                progress=0,
            )

        assert "errorCode" not in captured_body


class TestCompleteWorkerPhase:
    @pytest.mark.asyncio
    async def test_completed_steps_is_string_array(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            result = await client.complete_worker_phase(
                task_id="task_1",
                tenant_id="tenant_1",
                meeting_id="mtg_1",
                attempt_no=1,
                status="SUCCEEDED",
                completed_steps=["ASR", "DIARIZATION", "TRANSCRIPT_MERGE"],
            )

        completed_steps = captured_body["completedSteps"]
        assert isinstance(completed_steps, list)
        for step in completed_steps:
            assert isinstance(step, str)
        assert completed_steps == ["ASR", "DIARIZATION", "TRANSCRIPT_MERGE"]
        assert result.accepted is True

    @pytest.mark.asyncio
    async def test_request_body_fields(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            await client.complete_worker_phase(
                task_id="task_1",
                tenant_id="tenant_1",
                meeting_id="mtg_1",
                attempt_no=1,
                status="SUCCEEDED",
                completed_steps=["ASR"],
            )

        assert captured_body["tenantId"] == "tenant_1"
        assert captured_body["meetingId"] == "mtg_1"
        assert captured_body["taskId"] == "task_1"
        assert captured_body["phase"] == "WORKER_DAG"
        assert captured_body["status"] == "SUCCEEDED"
        assert "finishedAt" in captured_body


class TestFailTask:
    @pytest.mark.asyncio
    async def test_tenant_id_in_request_body(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            result = await client.fail_task(
                task_id="task_fail",
                tenant_id="my_tenant_42",
                attempt_no=2,
                failed_step="ASR",
                error_code="GPU_OOM",
                error_message="Out of memory",
            )

        assert captured_body["tenantId"] == "my_tenant_42"
        assert captured_body["taskId"] == "task_fail"
        assert captured_body["attemptNo"] == 2
        assert captured_body["failedStep"] == "ASR"
        assert captured_body["error"]["code"] == "GPU_OOM"
        assert captured_body["error"]["message"] == "Out of memory"
        assert captured_body["error"]["retryable"] is True
        assert "failedAt" in captured_body
        assert result.accepted is True

    @pytest.mark.asyncio
    async def test_retryable_defaults_to_true(self, client: JavaCallbackClient) -> None:
        captured_body: dict = {}

        async def mock_request(self_inner, method, path, body, task_id, attempt_no, trace_id, idempotency_key, max_retries=3):
            captured_body.update(body)
            return CallbackResponse(http_status=200, accepted=True)

        with patch.object(JavaCallbackClient, "_request", mock_request):
            await client.fail_task(
                task_id="t1",
                tenant_id="ten1",
                attempt_no=1,
                failed_step="DIARIZATION",
                error_code="TIMEOUT",
                error_message="Timed out",
                retryable=False,
            )

        assert captured_body["error"]["retryable"] is False