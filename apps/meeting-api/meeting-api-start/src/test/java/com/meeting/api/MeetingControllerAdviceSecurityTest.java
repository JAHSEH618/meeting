package com.meeting.api;

import com.meeting.api.adapter.meeting.MeetingControllerAdvice;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.llm.LlmProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingControllerAdviceSecurityTest {

    @Test
    void llmProviderTimeoutMapsToServiceUnavailableAndRetryable() {
        var advice = new MeetingControllerAdvice();
        var response = advice.handleLlmProvider(
            new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "timed out")
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var err = response.getBody().error();
        assertThat(err.code()).isEqualTo(ErrorCode.LLM_PROVIDER_TIMEOUT);
        assertThat(err.retryable()).isTrue();
    }

    @Test
    void llmRateLimitMapsTo429() {
        var advice = new MeetingControllerAdvice();
        var response = advice.handleLlmProvider(
            new LlmProviderException(ErrorCode.LLM_RATE_LIMIT, "slow down")
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.LLM_RATE_LIMIT);
    }

    @Test
    void llmSchemaInvalidIsNonRetryable() {
        var advice = new MeetingControllerAdvice();
        var response = advice.handleLlmProvider(
            new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "bad json")
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().retryable()).isFalse();
    }

    @Test
    void transcriptVersionConflictMapsTo409WithExpectedAndActual() {
        var advice = new MeetingControllerAdvice();
        var response = advice.handleTranscriptVersionConflict(
            new TranscriptApplicationService.TranscriptVersionConflictException(7, 3)
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var err = response.getBody().error();
        assertThat(err.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) err.details();
        assertThat(details).containsEntry("expectedVersion", 3);
        assertThat(details).containsEntry("actualVersion", 7);
    }

    @Test
    void minutesVersionConflictMapsTo409() {
        var advice = new MeetingControllerAdvice();
        var response = advice.handleMinutesVersionConflict(
            new MinutesApplicationService.VersionConflictException("version mismatch")
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
    }
}
