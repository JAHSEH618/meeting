package com.meeting.api.domain.speaker;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Speaker profile aggregate root. Owns the {@code consent_status} state machine
 * ({@code ACTIVE -> REVOKED -> DELETED}) and acts as the consent authority for any
 * downstream speaker embedding / matching writes.
 *
 * Embeddings are persisted separately (KMS envelope encrypted, see
 * {@link SpeakerEmbeddingRepository}); this aggregate carries no plaintext key material.
 */
public final class SpeakerProfile {
    private final String id;
    private final String tenantId;
    private final String personId;
    private String displayNameSnapshot;
    private String consentStatus;
    private String consentSource;
    private String consentVersion;
    private final String enrolledBy;
    private OffsetDateTime revokedAt;
    private OffsetDateTime deletedAt;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private SpeakerProfile(
        String id, String tenantId, String personId, String displayNameSnapshot,
        String consentStatus, String consentSource, String consentVersion,
        String enrolledBy,
        OffsetDateTime revokedAt, OffsetDateTime deletedAt,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.personId = requireText(personId, "personId");
        this.displayNameSnapshot = displayNameSnapshot;
        this.consentStatus = Objects.requireNonNull(consentStatus, "consentStatus");
        this.consentSource = consentSource;
        this.consentVersion = consentVersion;
        this.enrolledBy = enrolledBy;
        this.revokedAt = revokedAt;
        this.deletedAt = deletedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static SpeakerProfile create(
        String id, String tenantId, String personId, String displayName,
        String consentSource, String consentVersion, String enrolledBy, OffsetDateTime now
    ) {
        return new SpeakerProfile(
            id, tenantId, personId, displayName,
            "ACTIVE", consentSource, consentVersion, enrolledBy,
            null, null, now, now
        );
    }

    public static SpeakerProfile restore(
        String id, String tenantId, String personId, String displayName,
        String consentStatus, String consentSource, String consentVersion, String enrolledBy,
        OffsetDateTime revokedAt, OffsetDateTime deletedAt,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
        return new SpeakerProfile(
            id, tenantId, personId, displayName,
            consentStatus, consentSource, consentVersion, enrolledBy,
            revokedAt, deletedAt, createdAt, updatedAt
        );
    }

    public void revoke(OffsetDateTime now) {
        if (!"ACTIVE".equals(consentStatus)) {
            throw new IllegalStateException("only ACTIVE profile can be revoked");
        }
        consentStatus = "REVOKED";
        revokedAt = now;
        updatedAt = now;
    }

    public void delete(OffsetDateTime now) {
        if ("DELETED".equals(consentStatus)) {
            return;
        }
        consentStatus = "DELETED";
        deletedAt = now;
        if (revokedAt == null) {
            revokedAt = now;
        }
        updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String personId() { return personId; }
    public String displayNameSnapshot() { return displayNameSnapshot; }
    public String consentStatus() { return consentStatus; }
    public String consentSource() { return consentSource; }
    public String consentVersion() { return consentVersion; }
    public String enrolledBy() { return enrolledBy; }
    public OffsetDateTime revokedAt() { return revokedAt; }
    public OffsetDateTime deletedAt() { return deletedAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public boolean isActive() { return "ACTIVE".equals(consentStatus); }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
