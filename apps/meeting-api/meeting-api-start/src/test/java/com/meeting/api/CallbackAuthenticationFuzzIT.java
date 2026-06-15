package com.meeting.api;

import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceRepository;
import com.meeting.api.infrastructure.persistence.task.JdbcCallbackNonceRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fuzz test for callback authentication covering 7 security dimensions:
 * 1. HMAC signature validation
 * 2. Timestamp skew (±5 min)
 * 3. Nonce deduplication
 * 4. Idempotency-Key body hash (tested at app layer)
 * 5. Attempt number matching (tested at app layer)
 * 6. Lease owner validation (tested at app layer)
 * 7. Tenant/meeting relationship (tested at app layer)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackAuthenticationFuzzIT {
    private static final String SECRET = "test-callback-hmac-secret";
    private static final String TENANT = "tenant_fuzz_auth";
    private static final String WORKER = "worker_fuzz_01";
    private static final String TASK = "task_fuzz_01";
    private static final String STEP = "ASR";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-15T10:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private CallbackNonceRepository nonceRepository;
    private CallbackSecurityVerifier verifier;
    private Clock fixedClock;

    @BeforeAll
    void startAndMigrate() {
        TestcontainersDockerPreflight.assumeDockerAvailable();

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("meeting_test")
            .withUsername("meeting")
            .withPassword("meeting_test");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        ds = new SingleConnectionDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        nonceRepository = new JdbcCallbackNonceRepository(jdbc);
        fixedClock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        verifier = new CallbackSecurityVerifier(SECRET, 300, fixedClock, nonceRepository);
    }

    @AfterAll
    void stop() {
        if (ds != null) ds.destroy();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Statement stmt = ds.getConnection().createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
            stmt.execute("DELETE FROM callback_nonces WHERE tenant_id = '" + TENANT + "'");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'Fuzz Auth') ON CONFLICT DO NOTHING");
        }
    }

    // ========== Dimension 1: HMAC Signature Validation ==========

    @Test
    void validHmacPasses() {
        CallbackMetadata metadata = validMetadata();

        // Should not throw
        verifier.verify(metadata, TENANT, WORKER, TASK, STEP);
    }

    @Test
    void invalidHmacReturns401() {
        CallbackMetadata metadata = metadataBuilder()
            .signature("hmac-sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
            .build();

        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback signature mismatch");
    }

    @Test
    void missingHmacPrefixReturns401() {
        CallbackMetadata metadata = metadataBuilder()
            .signature("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
            .build();

        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing callback signature");
    }

    @Test
    void nullSignatureReturns401() {
        CallbackMetadata metadata = metadataBuilder()
            .signature(null)
            .build();

        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing callback signature");
    }

    // ========== Dimension 2: Timestamp Skew (±5 min) ==========

    @Test
    void timestampWithin5MinSucceeds() {
        // 4 minutes in the past
        CallbackMetadata metadata = metadataBuilder()
            .timestamp(NOW.minusMinutes(4))
            .buildWithValidSignature();

        verifier.verify(metadata, TENANT, WORKER, TASK, STEP);
    }

    @Test
    void timestampExactly5MinOldSucceeds() {
        CallbackMetadata metadata = metadataBuilder()
            .timestamp(NOW.minusMinutes(5))
            .buildWithValidSignature();

        verifier.verify(metadata, TENANT, WORKER, TASK, STEP);
    }

    @Test
    void timestampOver5MinOldReturns401() {
        // 6 minutes in the past
        CallbackMetadata metadata = metadataBuilder()
            .timestamp(NOW.minusMinutes(6))
            .buildWithValidSignature();

        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback timestamp outside allowed skew");
    }

    @Test
    void futureTimestampWithin5MinSucceeds() {
        // 4 minutes in the future
        CallbackMetadata metadata = metadataBuilder()
            .timestamp(NOW.plusMinutes(4))
            .buildWithValidSignature();

        verifier.verify(metadata, TENANT, WORKER, TASK, STEP);
    }

    @Test
    void futureTimestampOver5MinReturns401() {
        // 6 minutes in the future
        CallbackMetadata metadata = metadataBuilder()
            .timestamp(NOW.plusMinutes(6))
            .buildWithValidSignature();

        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback timestamp outside allowed skew");
    }

    // ========== Dimension 3: Nonce Deduplication ==========

    @Test
    void duplicateNonceReturns409() {
        CallbackMetadata metadata = validMetadata();

        // First call succeeds
        verifier.verify(metadata, TENANT, WORKER, TASK, STEP);

        // Second call with same nonce fails
        assertThatThrownBy(() -> verifier.verify(metadata, TENANT, WORKER, TASK, STEP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback nonce already used");
    }

    @Test
    void differentNoncesSameTaskSucceeds() {
        CallbackMetadata first = metadataBuilder()
            .nonce("nonce_001")
            .buildWithValidSignature();

        CallbackMetadata second = metadataBuilder()
            .nonce("nonce_002")
            .buildWithValidSignature();

        verifier.verify(first, TENANT, WORKER, TASK, STEP);
        verifier.verify(second, TENANT, WORKER, TASK, STEP);
    }

    @Test
    void sameNonceDifferentTenantSucceeds() throws Exception {
        String otherTenant = "tenant_other";

        // Setup other tenant
        try (Statement stmt = ds.getConnection().createStatement()) {
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + otherTenant + "', 'Other') ON CONFLICT DO NOTHING");
        }

        CallbackMetadata first = validMetadata();
        CallbackMetadata second = validMetadata(); // Same nonce

        verifier.verify(first, TENANT, WORKER, TASK, STEP);
        verifier.verify(second, otherTenant, WORKER, TASK, STEP);
    }

    // ========== Helper Methods ==========

    private CallbackMetadata validMetadata() {
        return metadataBuilder().buildWithValidSignature();
    }

    private MetadataBuilder metadataBuilder() {
        return new MetadataBuilder();
    }

    private class MetadataBuilder {
        private String workerId = WORKER;
        private int attemptNo = 1;
        private String leaseOwner = "worker_fuzz_01:task_fuzz_01:1";
        private String httpMethod = "PATCH";
        private String requestId = "req_" + UUID.randomUUID().toString().substring(0, 8);
        private String traceId = "trace_" + UUID.randomUUID().toString().substring(0, 8);
        private OffsetDateTime timestamp = NOW;
        private String nonce = "nonce_" + UUID.randomUUID().toString().substring(0, 8);
        private String idempotencyKey = "idem_" + UUID.randomUUID().toString().substring(0, 8);
        private String signature = null;
        private String urlPathWithQuery = "/internal/processing-tasks/task_fuzz_01/steps/ASR";
        private String bodySha256 = sha256("{}");

        MetadataBuilder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        MetadataBuilder attemptNo(int attemptNo) {
            this.attemptNo = attemptNo;
            return this;
        }

        MetadataBuilder leaseOwner(String leaseOwner) {
            this.leaseOwner = leaseOwner;
            return this;
        }

        MetadataBuilder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        MetadataBuilder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        MetadataBuilder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        MetadataBuilder timestamp(OffsetDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        MetadataBuilder nonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        MetadataBuilder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        MetadataBuilder signature(String signature) {
            this.signature = signature;
            return this;
        }

        MetadataBuilder urlPathWithQuery(String urlPathWithQuery) {
            this.urlPathWithQuery = urlPathWithQuery;
            return this;
        }

        MetadataBuilder bodySha256(String bodySha256) {
            this.bodySha256 = bodySha256;
            return this;
        }

        CallbackMetadata build() {
            return new CallbackMetadata(
                workerId,
                attemptNo,
                leaseOwner,
                httpMethod,
                requestId,
                traceId,
                timestamp,
                nonce,
                idempotencyKey,
                signature,
                urlPathWithQuery,
                bodySha256
            );
        }

        CallbackMetadata buildWithValidSignature() {
            String signingString = timestamp + "\n"
                + nonce + "\n"
                + httpMethod + "\n"
                + urlPathWithQuery + "\n"
                + bodySha256;
            this.signature = "hmac-sha256=" + hmacSha256(signingString, SECRET);
            return build();
        }
    }

    private static String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute hmac", e);
        }
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute sha256", e);
        }
    }
}
