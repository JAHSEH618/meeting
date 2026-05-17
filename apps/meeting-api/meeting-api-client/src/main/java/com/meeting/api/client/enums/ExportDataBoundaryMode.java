package com.meeting.api.client.enums;

/**
 * Data-boundary mode applied while rendering an export — single source of
 * truth: packages/meeting-contracts/schemas/common/enums.yaml.
 *
 * <p>Phase 1 always emits {@link #FULL}; {@link #REDACTED} is reserved for
 * Phase 2+ when speaker / PII redaction is wired in.
 */
public enum ExportDataBoundaryMode {
    FULL,
    REDACTED
}
