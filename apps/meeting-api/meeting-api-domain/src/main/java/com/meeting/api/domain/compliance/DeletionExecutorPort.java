package com.meeting.api.domain.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import java.util.List;
import java.util.Map;

/**
 * Strategy port that executes the actual deletion for a single
 * {@link DeletionJob} scope. Implementations are picked from a
 * {@code DeletionExecutorRegistry} by {@link #supportedScope()}.
 *
 * <p>Implementations should be self-contained: own their target
 * aggregates' soft-delete + TOS object delete + KMS DEK destruction
 * inside short per-row transactions (NOT inside one giant
 * @Transactional). Failures collect into {@code failedItems} rather
 * than aborting the whole run.
 */
public interface DeletionExecutorPort {

    DeletionScopeType supportedScope();

    DeletionOutcome execute(String tenantId, String scopeId, String executorId);

    /**
     * Aggregated result of executing one scope. Counts go into the
     * deletion_certificate JSON columns; {@code failedItems} flips the
     * job to PARTIAL_FAILED when non-empty.
     */
    record DeletionOutcome(
        Map<String, Object> deletedRows,
        Map<String, Object> deletedFiles,
        Map<String, Object> kmsKeysDestroyed,
        List<String> failedItems
    ) {

        public DeletionOutcome {
            deletedRows = deletedRows == null ? Map.of() : Map.copyOf(deletedRows);
            deletedFiles = deletedFiles == null ? Map.of() : Map.copyOf(deletedFiles);
            kmsKeysDestroyed = kmsKeysDestroyed == null ? Map.of() : Map.copyOf(kmsKeysDestroyed);
            failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        }

        public boolean isFullSuccess() { return failedItems.isEmpty(); }
    }
}
