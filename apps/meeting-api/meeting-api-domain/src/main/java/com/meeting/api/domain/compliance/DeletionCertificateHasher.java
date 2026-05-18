package com.meeting.api.domain.compliance;

/**
 * Computes the {@code certificate_hash} for a finished
 * {@link DeletionJob}. Hash inputs are normalised so re-running the
 * same outcome yields the same hex string (canonical-JSON SHA-256).
 *
 * <p>Implementations live in infrastructure (depends on ObjectMapper).
 */
public interface DeletionCertificateHasher {

    /**
     * @param tenantId tenant the deletion targets
     * @param jobId    DeletionJob aggregate id
     * @param outcome  rolled-up executor outcome
     * @return hex digest prefixed with {@code "sha256:"}
     */
    String compute(
        String tenantId,
        String jobId,
        DeletionExecutorPort.DeletionOutcome outcome
    );
}
