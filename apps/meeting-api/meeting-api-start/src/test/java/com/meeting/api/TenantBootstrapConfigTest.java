package com.meeting.api;

import com.meeting.api.start.config.TenantBootstrapConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBootstrapConfigTest {

    @Test
    void devSeedCreatesInMemoryAuthActorRowsForAuditForeignKeys() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

        TenantBootstrapConfig.DevSeed seed = new TenantBootstrapConfig.DevSeed(jdbc, "tenant_default");
        seed.run(new DefaultApplicationArguments());

        assertThat(jdbc.updateCalls)
            .anySatisfy(call -> {
                assertThat(call.get(0).toString()).contains("INSERT INTO users");
                assertThat(call).contains("user_admin", "tenant_default");
            })
            .anySatisfy(call -> {
                assertThat(call.get(0).toString()).contains("INSERT INTO persons");
                assertThat(call).contains("person_admin", "tenant_default");
            })
            .anySatisfy(call -> {
                assertThat(call.get(0).toString()).contains("INSERT INTO user_person_links");
                assertThat(call).contains("user_admin", "person_admin", "tenant_default");
            });
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<List<Object>> updateCalls = new ArrayList<>();

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            return Collections.emptyList();
        }

        @Override
        public int update(String sql, Object... args) {
            List<Object> flattened = new ArrayList<>();
            flattened.add(sql);
            for (Object argument : args) {
                if (argument instanceof Object[] values) {
                    flattened.addAll(List.of(values));
                } else {
                    flattened.add(argument);
                }
            }
            updateCalls.add(flattened);
            return 1;
        }
    }
}
