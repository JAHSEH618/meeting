package com.meeting.api.adapter.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CallbackRequestIdentityValidatorTest {

    @Test
    void acceptsBodyTaskIdThatMatchesPath() {
        assertThatCode(() -> CallbackRequestIdentityValidator.requireTaskIdMatchesPath(
            Map.of("taskId", "task_01"),
            "task_01"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyTaskIdThatDiffersFromPath() {
        assertThatThrownBy(() -> CallbackRequestIdentityValidator.requireTaskIdMatchesPath(
            Map.of("taskId", "task_02"),
            "task_01"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("taskId")
            .hasMessageContaining("path=task_01")
            .hasMessageContaining("body=task_02");
    }

    @Test
    void rejectsMissingBodyTaskId() {
        assertThatThrownBy(() -> CallbackRequestIdentityValidator.requireTaskIdMatchesPath(
            Map.of("tenantId", "tenant_01"),
            "task_01"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required field: taskId");
    }

    @Test
    void acceptsBodyAttemptNoThatMatchesSignedHeader() {
        assertThatCode(() -> CallbackRequestIdentityValidator.requireAttemptNoMatchesHeader(
            Map.of("attemptNo", 2),
            2
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyAttemptNoThatDiffersFromSignedHeader() {
        assertThatThrownBy(() -> CallbackRequestIdentityValidator.requireAttemptNoMatchesHeader(
            Map.of("attemptNo", 2),
            1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attemptNo")
            .hasMessageContaining("header=1")
            .hasMessageContaining("body=2");
    }

    @Test
    void rejectsMissingBodyAttemptNo() {
        assertThatThrownBy(() -> CallbackRequestIdentityValidator.requireAttemptNoMatchesHeader(
            Map.of("tenantId", "tenant_01"),
            1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required field: attemptNo");
    }
}
