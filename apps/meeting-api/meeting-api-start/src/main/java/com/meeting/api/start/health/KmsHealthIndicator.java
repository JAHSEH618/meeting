package com.meeting.api.start.health;

import com.meeting.api.domain.kms.KmsGateway;
import com.meeting.api.domain.kms.KmsGateway.GeneratedDataKey;
import java.util.Arrays;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 8.1.2.d — runs a roundtrip wrap → unwrap on the configured
 * {@link KmsGateway}. We use a tiny synthetic tenant id so the probe
 * does not pollute real tenant key history (the local KMS gateway
 * derives keys deterministically from the tenant id + key id, so this
 * is cheap; a hosted KMS may be billed per generateDataKey call —
 * adjust the schedule in that case).
 */
@Component("kms")
public class KmsHealthIndicator implements HealthIndicator {

    private static final String PROBE_TENANT = "__kms_probe__";

    private final KmsGateway kms;

    public KmsHealthIndicator(KmsGateway kms) {
        this.kms = kms;
    }

    @Override
    public Health health() {
        try {
            GeneratedDataKey dek = kms.generateDataKey(PROBE_TENANT);
            byte[] unwrapped = kms.unwrapDataKey(PROBE_TENANT, dek.keyId(), dek.wrappedDek());
            try {
                boolean ok = Arrays.equals(dek.plaintextDek(), unwrapped);
                if (!ok) {
                    return Health.down()
                        .withDetail("reason", "unwrap mismatch")
                        .build();
                }
                return Health.up()
                    .withDetail("keyId", dek.keyId())
                    .build();
            } finally {
                Arrays.fill(unwrapped, (byte) 0);
                Arrays.fill(dek.plaintextDek(), (byte) 0);
            }
        } catch (Exception ex) {
            return Health.down()
                .withDetail("error", ex.getClass().getSimpleName())
                .withDetail("message", ex.getMessage())
                .build();
        }
    }
}
