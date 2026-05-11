package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class Meeting {
    private final String id;
    private final String tenantId;
    private final String title;
    private final SecurityLevel securityLevel;
    private final MeetingStatus status;
    private final String language;
    private final int transcriptVersion;
    private final int minutesVersion;
    private final OffsetDateTime createdAt;

    private Meeting(
        String id,
        String tenantId,
        String title,
        SecurityLevel securityLevel,
        MeetingStatus status,
        String language,
        int transcriptVersion,
        int minutesVersion,
        OffsetDateTime createdAt
    ) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.title = requireText(title, "title");
        this.securityLevel = Objects.requireNonNull(securityLevel, "securityLevel");
        this.status = Objects.requireNonNull(status, "status");
        this.language = requireText(language, "language");
        this.transcriptVersion = transcriptVersion;
        this.minutesVersion = minutesVersion;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Meeting create(String id, String tenantId, String title, SecurityLevel securityLevel, String language) {
        return new Meeting(
            id,
            tenantId,
            title,
            securityLevel == null ? SecurityLevel.INTERNAL : securityLevel,
            MeetingStatus.CREATED,
            language == null || language.isBlank() ? "zh" : language,
            0,
            0,
            OffsetDateTime.now()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String title() {
        return title;
    }

    public SecurityLevel securityLevel() {
        return securityLevel;
    }

    public MeetingStatus status() {
        return status;
    }

    public String language() {
        return language;
    }

    public int transcriptVersion() {
        return transcriptVersion;
    }

    public int minutesVersion() {
        return minutesVersion;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
