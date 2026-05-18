package com.meeting.api.domain.breakglass;

import com.meeting.api.client.enums.BreakGlassStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Aggregate root for an emergency-access (break-glass) request
 * (Phase 7.4).
 *
 * <p>State machine:
 * <pre>
 *   PENDING ─► APPROVED   (approve; sets validFrom + validUntil)
 *           └─► REJECTED  (reject; reason required)
 *   APPROVED ─► EXPIRED   (auto, when now > validUntil)
 *            └─► REVOKED  (manual, before validUntil)
 * </pre>
 *
 * <p>The approver must differ from the requester
 * ({@link #approve} throws otherwise). Phase 1 uses single-approver
 * model; an {@code approversJson} field is reserved for future N-of-M.
 */
public final class BreakGlassRequest {

    /** Default approval window: 4 hours. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(4);

    private final String id;
    private final String tenantId;
    private final String requesterId;
    private final String scopeType;
    private final String scopeId;
    private final String reason;
    private final OffsetDateTime createdAt;

    private BreakGlassStatus status;
    private OffsetDateTime validFrom;
    private OffsetDateTime validUntil;
    private String approverId;
    private OffsetDateTime approvedAt;
    private OffsetDateTime rejectedAt;
    private String rejectReason;
    private OffsetDateTime revokedAt;
    private String revokedBy;
    private OffsetDateTime updatedAt;

    private BreakGlassRequest(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.requesterId = Objects.requireNonNull(b.requesterId, "requesterId");
        this.scopeType = Objects.requireNonNull(b.scopeType, "scopeType");
        this.scopeId = Objects.requireNonNull(b.scopeId, "scopeId");
        this.reason = Objects.requireNonNull(b.reason, "reason");
        this.status = b.status == null ? BreakGlassStatus.PENDING : b.status;
        this.validFrom = b.validFrom;
        this.validUntil = b.validUntil;
        this.approverId = b.approverId;
        this.approvedAt = b.approvedAt;
        this.rejectedAt = b.rejectedAt;
        this.rejectReason = b.rejectReason;
        this.revokedAt = b.revokedAt;
        this.revokedBy = b.revokedBy;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
        this.updatedAt = b.updatedAt == null ? this.createdAt : b.updatedAt;

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
    public String requesterId() { return requesterId; }
    public String scopeType() { return scopeType; }
    public String scopeId() { return scopeId; }
    public String reason() { return reason; }
    public BreakGlassStatus status() { return status; }
    public OffsetDateTime validFrom() { return validFrom; }
    public OffsetDateTime validUntil() { return validUntil; }
    public String approverId() { return approverId; }
    public OffsetDateTime approvedAt() { return approvedAt; }
    public OffsetDateTime rejectedAt() { return rejectedAt; }
    public String rejectReason() { return rejectReason; }
    public OffsetDateTime revokedAt() { return revokedAt; }
    public String revokedBy() { return revokedBy; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }

    public boolean isActiveAt(OffsetDateTime at) {
        if (status != BreakGlassStatus.APPROVED) return false;
        if (validFrom == null || validUntil == null) return false;
        return !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    /** PENDING → APPROVED. */
    public void approve(String approverId, OffsetDateTime at, Duration window) {
        Objects.requireNonNull(approverId, "approverId");
        Objects.requireNonNull(at, "at");
        if (approverId.equals(requesterId)) {
            throw new SelfApprovalForbiddenException(requesterId);
        }
        if (status != BreakGlassStatus.PENDING) {
            throw new IllegalStateException(
                "BreakGlassRequest " + id + " cannot transition " + status + " -> APPROVED"
            );
        }
        this.status = BreakGlassStatus.APPROVED;
        this.approverId = approverId;
        this.approvedAt = at;
        this.validFrom = at;
        this.validUntil = at.plus(window == null ? DEFAULT_WINDOW : window);
        touch(at);
    }

    /** PENDING → REJECTED. */
    public void reject(String approverId, String reason, OffsetDateTime at) {
        Objects.requireNonNull(approverId, "approverId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reject reason must not be blank");
        }
        if (status != BreakGlassStatus.PENDING) {
            throw new IllegalStateException(
                "BreakGlassRequest " + id + " cannot transition " + status + " -> REJECTED"
            );
        }
        this.status = BreakGlassStatus.REJECTED;
        this.approverId = approverId;
        this.rejectedAt = at;
        this.rejectReason = reason;
        touch(at);
    }

    /** APPROVED → REVOKED. */
    public void revoke(String revokedBy, OffsetDateTime at) {
        Objects.requireNonNull(revokedBy, "revokedBy");
        if (status != BreakGlassStatus.APPROVED) {
            throw new IllegalStateException(
                "BreakGlassRequest " + id + " cannot transition " + status + " -> REVOKED"
            );
        }
        this.status = BreakGlassStatus.REVOKED;
        this.revokedAt = at;
        this.revokedBy = revokedBy;
        touch(at);
    }

    /** APPROVED → EXPIRED (called by the scanner). Idempotent if already terminal. */
    public void expire(OffsetDateTime at) {
        if (status != BreakGlassStatus.APPROVED) return;
        this.status = BreakGlassStatus.EXPIRED;
        touch(at);
    }

    private void touch(OffsetDateTime at) {
        this.updatedAt = at == null ? OffsetDateTime.now() : at;
    }

    /** Thrown when an actor tries to approve their own break-glass request. */
    public static final class SelfApprovalForbiddenException extends RuntimeException {
        private final String requesterId;
        public SelfApprovalForbiddenException(String requesterId) {
            super("requester " + requesterId + " cannot approve their own break-glass request");
            this.requesterId = requesterId;
        }
        public String requesterId() { return requesterId; }
    }

    public static final class Builder {
        private String id;
        private String tenantId;
        private String requesterId;
        private String scopeType;
        private String scopeId;
        private String reason;
        private BreakGlassStatus status;
        private OffsetDateTime validFrom;
        private OffsetDateTime validUntil;
        private String approverId;
        private OffsetDateTime approvedAt;
        private OffsetDateTime rejectedAt;
        private String rejectReason;
        private OffsetDateTime revokedAt;
        private String revokedBy;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder requesterId(String v) { this.requesterId = v; return this; }
        public Builder scopeType(String v) { this.scopeType = v; return this; }
        public Builder scopeId(String v) { this.scopeId = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder status(BreakGlassStatus v) { this.status = v; return this; }
        public Builder validFrom(OffsetDateTime v) { this.validFrom = v; return this; }
        public Builder validUntil(OffsetDateTime v) { this.validUntil = v; return this; }
        public Builder approverId(String v) { this.approverId = v; return this; }
        public Builder approvedAt(OffsetDateTime v) { this.approvedAt = v; return this; }
        public Builder rejectedAt(OffsetDateTime v) { this.rejectedAt = v; return this; }
        public Builder rejectReason(String v) { this.rejectReason = v; return this; }
        public Builder revokedAt(OffsetDateTime v) { this.revokedAt = v; return this; }
        public Builder revokedBy(String v) { this.revokedBy = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(OffsetDateTime v) { this.updatedAt = v; return this; }

        public BreakGlassRequest build() { return new BreakGlassRequest(this); }
    }
}
