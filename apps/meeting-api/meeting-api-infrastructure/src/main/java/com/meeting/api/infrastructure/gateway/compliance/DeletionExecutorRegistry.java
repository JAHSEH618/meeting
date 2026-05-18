package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Lookup helper that routes a {@link DeletionScopeType} to the
 * registered {@link DeletionExecutorPort}. Spring injects every
 * {@link DeletionExecutorPort} bean and we index by
 * {@link DeletionExecutorPort#supportedScope()}.
 */
@Component
public class DeletionExecutorRegistry {

    private final Map<DeletionScopeType, DeletionExecutorPort> byScope;

    public DeletionExecutorRegistry(List<DeletionExecutorPort> executors) {
        Map<DeletionScopeType, DeletionExecutorPort> map = new EnumMap<>(DeletionScopeType.class);
        for (DeletionExecutorPort executor : executors) {
            DeletionScopeType scope = executor.supportedScope();
            DeletionExecutorPort existing = map.put(scope, executor);
            if (existing != null) {
                throw new IllegalStateException(
                    "Multiple DeletionExecutorPort beans for scope " + scope
                        + ": " + existing.getClass().getName()
                        + " and " + executor.getClass().getName()
                );
            }
        }
        this.byScope = Map.copyOf(map);
    }

    /**
     * @return the executor for the scope, or empty if none registered.
     *         The runner translates empty into a job FAILED with a
     *         clear error message.
     */
    public java.util.Optional<DeletionExecutorPort> find(DeletionScopeType scope) {
        return java.util.Optional.ofNullable(byScope.get(scope));
    }
}
