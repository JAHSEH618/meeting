package com.meeting.api.domain.compliance;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate root for an asynchronous deletion job (Phase 7.3).
 *
 * <p>State machine:
 * <pre>
 *   REQUESTED ─► RUNNING ─► SUCCEEDED
 *                       └─► PARTIAL_FAILED   (some items failed)
 *                       └─► FAILED           (executor crashed)
 *   REQUESTED ─► BLOCKED_BY_LEGAL_HOLD       (rejected at runner-side
 *                                              second-check)
 *   PENDING_APPROVAL is reserved for phase 7.3+ approval workflows;
 *   the phase-1 application service skips it.
 * </pre>
 *
 * <p>The {@code certificateHash} is set by the runner once it generates
 * a {@link DeletionCertificate}. The aggregate stays a value-style
 * record-of-fields — it's persisted by repository and read by
 * runner / certificate generator.
 */
public final class DeletionJob {

    private final String id;
    private final String tenantId;
    private final DeletionScopeType scopeType;
    private final String scopeId;
    private final String requestedBy;
    private final String approvedBy;
    private final OffsetDateTime createdAt;

    private DeletionJobStatus status;
    private boolean legalHoldChecked;
    private Map<String, Object> deletedRowsJson;
    private Map<String, Object> deletedFilesJson;
    private Map<String, Object> kmsKeysDestroyedJson;
    private String certificateHash;
    private ErrorCode errorCode;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;

    private DeletionJob(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.scopeType = Objects.requireNonNull(b.scopeType, "scopeType");
        this.scopeId = Objects.requireNonNull(b.scopeId, "scopeId");
        this.requestedBy = Objects.requireNonNull(b.requestedBy, "requestedBy");
        this.approvedBy = b.approvedBy;
        this.status = b.status == null ? DeletionJobStatus.REQUESTED : b.status;
        this.legalHoldChecked = b.legalHoldChecked;
        this.deletedRowsJson = b.deletedRowsJson == null ? Map.of() : Map.copyOf(b.deletedRowsJson);
        this.deletedFilesJson = b.deletedFilesJson == null ? Map.of() : Map.copyOf(b.deletedFilesJson);
        this.kmsKeysDestroyedJson = b.kmsKeysDestroyedJson == null
            ? Map.of() : Map.copyOf(b.kmsKeysDestroyedJson);
        this.certificateHash = b.certificateHash;
        this.errorCode = b.errorCode;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
        this.updatedAt = b.updatedAt == null ? this.createdAt : b.updatedAt;
        this.finishedAt = b.finishedAt;

        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        if (requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy must not be blank");
        }
    }

    public static Builder builder() { return new Builder(); }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public DeletionScopeType scopeType() { return scopeType; }
    public String scopeId() { return scopeId; }
    public String requestedBy() { return requestedBy; }
    public String approvedBy() { return approvedBy; }
    public OffsetDateTime createdAt() { return createdAt; }
    public DeletionJobStatus status() { return status; }
    public boolean legalHoldChecked() { return legalHoldChecked; }
    public Map<String, Object> deletedRowsJson() { return deletedRowsJson; }
    public Map<String, Object> deletedFilesJson() { return deletedFilesJson; }
    public Map<String, Object> kmsKeysDestroyedJson() { return kmsKeysDestroyedJson; }
    public String certificateHash() { return certificateHash; }
    public ErrorCode errorCode() { return errorCode; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }

    /** REQUESTED → RUNNING. Marks legal-hold checked. */
    public void markRunning(OffsetDateTime at) {
        require(status == DeletionJobStatus.REQUESTED, "RUNNING");
        this.status = DeletionJobStatus.RUNNING;
        this.legalHoldChecked = true;
        touch(at);
    }

    /** REQUESTED → BLOCKED_BY_LEGAL_HOLD. */
    public void markBlockedByLegalHold(OffsetDateTime at) {
        require(status == DeletionJobStatus.REQUESTED, "BLOCKED_BY_LEGAL_HOLD");
        this.status = DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD;
        this.legalHoldChecked = true;
        this.errorCode = ErrorCode.DELETION_JOB_BLOCKED_BY_LEGAL_HOLD;
        this.finishedAt = at;
        touch(at);
    }

    /** RUNNING → SUCCEEDED. */
    public void markSucceeded(
        Map<String, Object> deletedRows,
        Map<String, Object> deletedFiles,
        Map<String, Object> kmsDestroyed,
        String certificateHash,
        OffsetDateTime at
    ) {
        require(status == DeletionJobStatus.RUNNING, "SUCCEEDED");
        this.status = DeletionJobStatus.SUCCEEDED;
        this.deletedRowsJson = deletedRows == null ? Map.of() : Map.copyOf(deletedRows);
        this.deletedFilesJson = deletedFiles == null ? Map.of() : Map.copyOf(deletedFiles);
        this.kmsKeysDestroyedJson = kmsDestroyed == null ? Map.of() : Map.copyOf(kmsDestroyed);
        this.certificateHash = certificateHash;
        this.errorCode = null;
        this.finishedAt = at;
        touch(at);
    }

    /** RUNNING → PARTIAL_FAILED. */
    public void markPartialFailed(
        Map<String, Object> deletedRows,
        Map<String, Object> deletedFiles,
        Map<String, Object> kmsDestroyed,
        String certificateHash,
        OffsetDateTime at
    ) {
        require(status == DeletionJobStatus.RUNNING, "PARTIAL_FAILED");
        this.status = DeletionJobStatus.PARTIAL_FAILED;
        this.deletedRowsJson = deletedRows == null ? Map.of() : Map.copyOf(deletedRows);
        this.deletedFilesJson = deletedFiles == null ? Map.of() : Map.copyOf(deletedFiles);
        this.kmsKeysDestroyedJson = kmsDestroyed == null ? Map.of() : Map.copyOf(kmsDestroyed);
        this.certificateHash = certificateHash;
        this.finishedAt = at;
        touch(at);
    }

    /** RUNNING → FAILED. */
    public void markFailed(ErrorCode errorCode, OffsetDateTime at) {
        Objects.requireNonNull(errorCode, "errorCode");
        require(status == DeletionJobStatus.RUNNING, "FAILED");
        this.status = DeletionJobStatus.FAILED;
        this.errorCode = errorCode;
        this.finishedAt = at;
        touch(at);
    }

    private void require(boolean predicate, String target) {
        if (!predicate) {
            throw new IllegalStateException(
                "DeletionJob " + id + " cannot transition " + status + " -> " + target
            );
        }
    }

    private void touch(OffsetDateTime at) {
        this.updatedAt = at == null ? OffsetDateTime.now() : at;
    }

    public static final class Builder {
        private String id;
        private String tenantId;
        private DeletionScopeType scopeType;
        private String scopeId;
        private String requestedBy;
        private String approvedBy;
        private DeletionJobStatus status;
        private boolean legalHoldChecked;
        private Map<String, Object> deletedRowsJson;
        private Map<String, Object> deletedFilesJson;
        private Map<String, Object> kmsKeysDestroyedJson;
        private String certificateHash;
        private ErrorCode errorCode;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime finishedAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder scopeType(DeletionScopeType v) { this.scopeType = v; return this; }
        public Builder scopeId(String v) { this.scopeId = v; return this; }
        public Builder requestedBy(String v) { this.requestedBy = v; return this; }
        public Builder approvedBy(String v) { this.approvedBy = v; return this; }
        public Builder status(DeletionJobStatus v) { this.status = v; return this; }
        public Builder legalHoldChecked(boolean v) { this.legalHoldChecked = v; return this; }
        public Builder deletedRowsJson(Map<String, Object> v) { this.deletedRowsJson = v; return this; }
        public Builder deletedFilesJson(Map<String, Object> v) { this.deletedFilesJson = v; return this; }
        public Builder kmsKeysDestroyedJson(Map<String, Object> v) { this.kmsKeysDestroyedJson = v; return this; }
        public Builder certificateHash(String v) { this.certificateHash = v; return this; }
        public Builder errorCode(ErrorCode v) { this.errorCode = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(OffsetDateTime v) { this.updatedAt = v; return this; }
        public Builder finishedAt(OffsetDateTime v) { this.finishedAt = v; return this; }

        public DeletionJob build() { return new DeletionJob(this); }
    }
}
