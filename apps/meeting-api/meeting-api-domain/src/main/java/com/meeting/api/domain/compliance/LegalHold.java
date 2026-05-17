package com.meeting.api.domain.compliance;

import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.client.enums.LegalHoldStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Aggregate root for a legal hold (Phase 7). A hold protects exactly
 * one (scopeType, scopeId) pair from deletion / hard mutation until
 * an authorised actor {@link #release releases} it.
 *
 * <p>State machine: {@link LegalHoldStatus#ACTIVE} →
 * {@link LegalHoldStatus#RELEASED}; one-way, no re-activation
 * (a new hold needs a fresh aggregate).
 */
public final class LegalHold {

    private final String id;
    private final String tenantId;
    private final LegalHoldScopeType scopeType;
    private final String scopeId;
    private final String reason;
    private final String requestedBy;
    private final String approvedBy;
    private final OffsetDateTime createdAt;

    private LegalHoldStatus status;
    private OffsetDateTime releasedAt;
    private String releasedBy;
    private String releaseReason;

    private LegalHold(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.scopeType = Objects.requireNonNull(b.scopeType, "scopeType");
        this.scopeId = Objects.requireNonNull(b.scopeId, "scopeId");
        this.reason = Objects.requireNonNull(b.reason, "reason");
        this.requestedBy = Objects.requireNonNull(b.requestedBy, "requestedBy");
        this.approvedBy = b.approvedBy;
        this.status = b.status == null ? LegalHoldStatus.ACTIVE : b.status;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
        this.releasedAt = b.releasedAt;
        this.releasedBy = b.releasedBy;
        this.releaseReason = b.releaseReason;

        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }

    public static Builder builder() { return new Builder(); }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public LegalHoldScopeType scopeType() { return scopeType; }
    public String scopeId() { return scopeId; }
    public String reason() { return reason; }
    public String requestedBy() { return requestedBy; }
    public String approvedBy() { return approvedBy; }
    public LegalHoldStatus status() { return status; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime releasedAt() { return releasedAt; }
    public String releasedBy() { return releasedBy; }
    public String releaseReason() { return releaseReason; }

    public boolean isActive() {
        return status == LegalHoldStatus.ACTIVE;
    }

    /** ACTIVE → RELEASED. Throws when already released. */
    public void release(String releasedBy, String releaseReason, OffsetDateTime at) {
        Objects.requireNonNull(releasedBy, "releasedBy");
        Objects.requireNonNull(releaseReason, "releaseReason");
        if (releaseReason.isBlank()) {
            throw new IllegalArgumentException("releaseReason must not be blank");
        }
        if (status != LegalHoldStatus.ACTIVE) {
            throw new IllegalStateException(
                "legal hold " + id + " already in status " + status
            );
        }
        this.status = LegalHoldStatus.RELEASED;
        this.releasedAt = at == null ? OffsetDateTime.now() : at;
        this.releasedBy = releasedBy;
        this.releaseReason = releaseReason;
    }

    public static final class Builder {
        private String id;
        private String tenantId;
        private LegalHoldScopeType scopeType;
        private String scopeId;
        private String reason;
        private String requestedBy;
        private String approvedBy;
        private LegalHoldStatus status;
        private OffsetDateTime createdAt;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private String releaseReason;

        public Builder id(String v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder scopeType(LegalHoldScopeType v) { this.scopeType = v; return this; }
        public Builder scopeId(String v) { this.scopeId = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder requestedBy(String v) { this.requestedBy = v; return this; }
        public Builder approvedBy(String v) { this.approvedBy = v; return this; }
        public Builder status(LegalHoldStatus v) { this.status = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder releasedAt(OffsetDateTime v) { this.releasedAt = v; return this; }
        public Builder releasedBy(String v) { this.releasedBy = v; return this; }
        public Builder releaseReason(String v) { this.releaseReason = v; return this; }

        public LegalHold build() { return new LegalHold(this); }
    }
}
