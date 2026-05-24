package com.meeting.api;

import com.meeting.api.start.config.ActiveTenantList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveTenantListTest {

    @Test
    void parsesSingleTenant() {
        assertThat(ActiveTenantList.parse("tenant_default"))
            .containsExactly("tenant_default");
    }

    @Test
    void parsesMultipleTenants() {
        assertThat(ActiveTenantList.parse("tenant_acme,tenant_emea"))
            .containsExactly("tenant_acme", "tenant_emea");
    }

    @Test
    void trimsWhitespace() {
        assertThat(ActiveTenantList.parse(" tenant_acme , tenant_emea "))
            .containsExactly("tenant_acme", "tenant_emea");
    }

    @Test
    void dropsEmptyAndBlankEntries() {
        // ',', ' , ', 'a,,b' all parsed an empty list/element previously — but
        // ProdProfileValidator only checked raw isBlank(), so ',' passed the
        // validator yet schedulers silently got an empty list.
        assertThat(ActiveTenantList.parse(",")).isEmpty();
        assertThat(ActiveTenantList.parse(" , ")).isEmpty();
        assertThat(ActiveTenantList.parse("a,,b")).containsExactly("a", "b");
    }

    @Test
    void nullOrBlankReturnsEmpty() {
        assertThat(ActiveTenantList.parse(null)).isEmpty();
        assertThat(ActiveTenantList.parse("")).isEmpty();
        assertThat(ActiveTenantList.parse("   ")).isEmpty();
    }
}
