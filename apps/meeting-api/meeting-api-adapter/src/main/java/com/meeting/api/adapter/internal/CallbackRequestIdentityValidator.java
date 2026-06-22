package com.meeting.api.adapter.internal;

import java.util.Map;

final class CallbackRequestIdentityValidator {
    private CallbackRequestIdentityValidator() {
    }

    static void requireTaskIdMatchesPath(Map<String, Object> payload, String pathTaskId) {
        String bodyTaskId = requiredString(payload, "taskId");
        if (!bodyTaskId.equals(pathTaskId)) {
            throw new IllegalArgumentException(
                "callback taskId mismatch: path=" + pathTaskId + " body=" + bodyTaskId
            );
        }
    }

    static void requireAttemptNoMatchesHeader(Map<String, Object> payload, int headerAttemptNo) {
        Object raw = payload.get("attemptNo");
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("missing required field: attemptNo");
        }
        int bodyAttemptNo = number.intValue();
        if (bodyAttemptNo != headerAttemptNo) {
            throw new IllegalArgumentException(
                "callback attemptNo mismatch: header=" + headerAttemptNo + " body=" + bodyAttemptNo
            );
        }
    }

    private static String requiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        String stringValue = value == null ? null : String.valueOf(value);
        if (stringValue == null || stringValue.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return stringValue;
    }
}
