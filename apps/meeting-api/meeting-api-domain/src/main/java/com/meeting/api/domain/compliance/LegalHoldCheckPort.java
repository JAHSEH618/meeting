package com.meeting.api.domain.compliance;

/**
 * Read-only port that answers "is this entity under a legal hold?".
 *
 * <p>Called from delete-like operations (delete meeting / delete document /
 * delete speaker profile / create export) before they take any
 * destructive action. The real JDBC implementation lands in phase 7
 * together with the {@code legal_holds} table writes; until then a
 * {@code NoOpLegalHoldCheckPort} returns {@code false} for every query.
 *
 * <p>Implementations should be cheap to call (sub-millisecond) — a
 * short in-memory TTL cache is acceptable as long as the cache is
 * invalidated when a hold is placed or released.
 */
public interface LegalHoldCheckPort {

    /**
     * @param scopeType One of {@code MEETING}, {@code DOCUMENT},
     *                  {@code SPEAKER_PROFILE}, {@code PROJECT}.
     * @param scopeId   The entity id within the scope.
     * @return {@code true} if an ACTIVE legal hold protects this entity.
     */
    boolean isProtected(String tenantId, String scopeType, String scopeId);
}
