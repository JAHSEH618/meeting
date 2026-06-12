package com.meeting.api;

import com.meeting.api.domain.auth.RefreshToken;
import com.meeting.api.infrastructure.persistence.auth.JdbcRefreshTokenRepository;
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

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcRefreshTokenRepositoryIT {
    private static final String TENANT = "tenant_refresh_test";

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private JdbcRefreshTokenRepository repository;

    @BeforeAll
    void startAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15"));
        postgres.start();

        ds = new SingleConnectionDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            true
        );

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        jdbc = new JdbcTemplate(ds);
        repository = new JdbcRefreshTokenRepository(jdbc);
    }

    @BeforeEach
    void setTenantContext() throws Exception {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SET app.tenant_id = '" + TENANT + "'");
        }
    }

    @AfterAll
    void stop() {
        if (ds != null) {
            ds.destroy();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void saveAndFind() {
        RefreshToken token = new RefreshToken("tok_1", "user_1", TENANT,
            OffsetDateTime.now().plusDays(30));
        repository.save(token);

        var found = repository.findByTokenId("tok_1");
        assertThat(found).isPresent();
        assertThat(found.get().userId()).isEqualTo("user_1");
    }
}
