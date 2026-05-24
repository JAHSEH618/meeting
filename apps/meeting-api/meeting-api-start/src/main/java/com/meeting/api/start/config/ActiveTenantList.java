package com.meeting.api.start.config;

import java.util.Arrays;
import java.util.List;

/**
 * Shared parser for the {@code meeting.tenants.active} CSV property and
 * the per-scheduler {@code meeting.<scheduler>.tenants} fallback. Used
 * by every background scheduler (outbox publisher, lease scanner,
 * deletion runner, break-glass scanner) and by
 * {@link ProdProfileValidator}.
 *
 * <p>Centralized so the validator and the schedulers always agree on
 * what "empty" means: a raw value of {@code ","} or {@code " , "}
 * previously passed the validator's {@code isBlank()} check but parsed
 * to an empty list at the scheduler, so the scheduler silently
 * processed zero tenants. Both call sites must go through this method.
 */
public final class ActiveTenantList {

    private ActiveTenantList() {}

    /**
     * Splits a CSV value on commas, trims each entry, and drops blanks.
     * Null/blank input returns an empty list.
     */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
