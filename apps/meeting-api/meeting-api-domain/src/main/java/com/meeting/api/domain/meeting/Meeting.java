package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final String createdBy;
    private final List<Participant> participants;

    private Meeting(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.tenantId = requireText(builder.tenantId, "tenantId");
        this.title = requireText(builder.title, "title");
        this.securityLevel = Objects.requireNonNull(builder.securityLevel, "securityLevel");
        this.status = Objects.requireNonNull(builder.status, "status");
        this.language = requireText(builder.language, "language");
        this.transcriptVersion = builder.transcriptVersion;
        this.minutesVersion = builder.minutesVersion;
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt");
        this.createdBy = builder.createdBy;
        this.participants = Collections.unmodifiableList(
            builder.participants != null ? new ArrayList<>(builder.participants) : List.of()
        );
    }

    /** Factory method — creates a new meeting in CREATED state. */
    public static Meeting create(
        String id, String tenantId, String title,
        SecurityLevel securityLevel, String language,
        List<CreateMeetingCommand.ParticipantCommand> participantCommands,
        String createdBy
    ) {
        List<Participant> participants = new ArrayList<>();
        if (participantCommands != null) {
            for (var cmd : participantCommands) {
                participants.add(new Participant(cmd.personId(), cmd.displayName(), cmd.role()));
            }
        }
        return new Builder()
            .id(id)
            .tenantId(tenantId)
            .title(title)
            .securityLevel(securityLevel == null ? SecurityLevel.INTERNAL : securityLevel)
            .status(MeetingStatus.CREATED)
            .language(language == null || language.isBlank() ? "zh" : language)
            .transcriptVersion(0)
            .minutesVersion(0)
            .createdAt(OffsetDateTime.now())
            .createdBy(createdBy)
            .participants(participants)
            .build();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public Meeting markProcessing() {
        if (status == MeetingStatus.PROCESSING) {
            return this;
        }
        if (status != MeetingStatus.CREATED) {
            throw new IllegalStateException("meeting can only start processing from CREATED");
        }
        return copyWithStatus(MeetingStatus.PROCESSING);
    }

    public Meeting markDeleted() {
        if (status == MeetingStatus.DELETED) {
            throw new IllegalStateException("meeting is already deleted");
        }
        return copyWithStatus(MeetingStatus.DELETED);
    }

    private Meeting copyWithStatus(MeetingStatus nextStatus) {
        return new Builder()
            .id(id)
            .tenantId(tenantId)
            .title(title)
            .securityLevel(securityLevel)
            .status(nextStatus)
            .language(language)
            .transcriptVersion(transcriptVersion)
            .minutesVersion(minutesVersion)
            .createdAt(createdAt)
            .createdBy(createdBy)
            .participants(participants)
            .build();
    }

    // ── Accessors ─────────────────────────────────────────────

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String title() { return title; }
    public SecurityLevel securityLevel() { return securityLevel; }
    public MeetingStatus status() { return status; }
    public String language() { return language; }
    public int transcriptVersion() { return transcriptVersion; }
    public int minutesVersion() { return minutesVersion; }
    public OffsetDateTime createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
    public List<Participant> participants() { return participants; }

    // ── Builder ───────────────────────────────────────────────

    public static final class Builder {
        private String id;
        private String tenantId;
        private String title;
        private SecurityLevel securityLevel;
        private MeetingStatus status;
        private String language;
        private int transcriptVersion;
        private int minutesVersion;
        private OffsetDateTime createdAt;
        private String createdBy;
        private List<Participant> participants;

        public Builder id(String v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder securityLevel(SecurityLevel v) { this.securityLevel = v; return this; }
        public Builder status(MeetingStatus v) { this.status = v; return this; }
        public Builder language(String v) { this.language = v; return this; }
        public Builder transcriptVersion(int v) { this.transcriptVersion = v; return this; }
        public Builder minutesVersion(int v) { this.minutesVersion = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder createdBy(String v) { this.createdBy = v; return this; }
        public Builder participants(List<Participant> v) { this.participants = v; return this; }

        public Meeting build() {
            return new Meeting(this);
        }
    }

    public record Participant(String personId, String displayName, String role) {}
}
