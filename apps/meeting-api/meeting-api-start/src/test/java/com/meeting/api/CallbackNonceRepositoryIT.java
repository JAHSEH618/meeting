package com.meeting.api;

import com.meeting.api.domain.task.CallbackNonceRepository;
import com.meeting.api.start.MeetingApiApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MeetingApiApplication.class)
@ActiveProfiles("test")
@Import(SpringBootTestSupportConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CallbackNonceRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
        .withDatabaseName("meeting_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CallbackNonceRepository nonceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantId;

    @BeforeEach
    void setUp() {
        tenantId = "tenant_" + System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Test Tenant");
        jdbcTemplate.update("SET app.tenant_id = ?", tenantId);
    }

    @Test
    void shouldRecordNonceSuccessfully() {
        String nonce = "nonce_" + System.currentTimeMillis();
        boolean recorded = nonceRepository.record(tenantId, nonce, "worker1", "task1", "ASR");

        assertThat(recorded).isTrue();
        assertThat(nonceRepository.exists(tenantId, nonce)).isTrue();
    }

    @Test
    void shouldRejectDuplicateNonce() {
        String nonce = "nonce_" + System.currentTimeMillis();
        nonceRepository.record(tenantId, nonce, "worker1", "task1", "ASR");

        // 尝试重放
        boolean replayAttempt = nonceRepository.record(tenantId, nonce, "worker1", "task1", "ASR");

        assertThat(replayAttempt).isFalse();
    }

    @Test
    void shouldCleanupExpiredNonces() {
        String nonce1 = "nonce_old_" + System.currentTimeMillis();
        String nonce2 = "nonce_new_" + System.currentTimeMillis();

        nonceRepository.record(tenantId, nonce1, "worker1", "task1", "ASR");
        nonceRepository.record(tenantId, nonce2, "worker1", "task2", "DIARIZATION");

        // 手动设置 nonce1 为过期
        jdbcTemplate.update(
            "UPDATE callback_nonces SET expires_at = ? WHERE tenant_id = ? AND nonce = ?",
            OffsetDateTime.now().minusMinutes(10),
            tenantId,
            nonce1
        );

        int cleaned = nonceRepository.cleanupExpired(OffsetDateTime.now());

        assertThat(cleaned).isGreaterThanOrEqualTo(1);
        assertThat(nonceRepository.exists(tenantId, nonce1)).isFalse();
        assertThat(nonceRepository.exists(tenantId, nonce2)).isTrue();
    }

    @Test
    void shouldIsolateTenants() {
        String tenant1 = "tenant1_" + System.currentTimeMillis();
        String tenant2 = "tenant2_" + System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant1, "Tenant 1");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant2, "Tenant 2");

        String nonce = "shared_nonce_" + System.currentTimeMillis();

        nonceRepository.record(tenant1, nonce, "worker1", "task1", "ASR");

        // 不同租户可以使用相同的 nonce
        boolean recorded = nonceRepository.record(tenant2, nonce, "worker1", "task2", "ASR");
        assertThat(recorded).isTrue();
    }
}
