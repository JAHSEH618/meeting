package com.meeting.api;

import com.meeting.api.start.health.PostgresRlsHealthIndicator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

class PostgresRlsHealthIndicatorTest {

    @Test
    void reportsUpWhenRlsHidesAllTenantRows() throws Exception {
        // tenants is guaranteed non-empty (TenantBootstrap), so a zero count
        // without tenant context means the tenant_self policy filtered it.
        PostgresRlsHealthIndicator h = new PostgresRlsHealthIndicator(dataSourceReturningCount(0L));

        Health out = h.health();

        assertThat(out.getStatus()).isEqualTo(Status.UP);
        assertThat(out.getDetails()).containsEntry("probedTable", "tenants");
        assertThat(out.getDetails()).doesNotContainKey("rls");
    }

    @Test
    void reportsDownRlsBypassedWhenTenantRowsAreVisibleWithoutContext() throws Exception {
        // A BYPASSRLS role (e.g. the bootstrap superuser) sees the seeded
        // tenant rows — the exact misconfiguration this probe exists to catch.
        PostgresRlsHealthIndicator h = new PostgresRlsHealthIndicator(dataSourceReturningCount(3L));

        Health out = h.health();

        assertThat(out.getStatus()).isEqualTo(Status.DOWN);
        assertThat(out.getDetails()).containsEntry("rls", "bypassed");
        assertThat(out.getDetails()).containsEntry("visibleRows", 3L);
    }

    @Test
    void reportsUpWhenProbeIsDeniedByPermissions() throws Exception {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        Statement stmt = Mockito.mock(Statement.class);
        Mockito.when(dataSource.getConnection()).thenReturn(conn);
        Mockito.when(conn.createStatement()).thenReturn(stmt);
        Mockito.when(stmt.executeQuery(anyString()))
            .thenThrow(new SQLException("ERROR: permission denied for table tenants"));

        Health out = new PostgresRlsHealthIndicator(dataSource).health();

        assertThat(out.getStatus()).isEqualTo(Status.UP);
        assertThat(out.getDetails()).containsEntry("note", "probe denied by RLS as expected");
    }

    @Test
    void reportsDownOnConnectivityFailure() throws Exception {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Mockito.when(dataSource.getConnection())
            .thenThrow(new SQLException("connection refused"));

        Health out = new PostgresRlsHealthIndicator(dataSource).health();

        assertThat(out.getStatus()).isEqualTo(Status.DOWN);
        assertThat(out.getDetails()).containsEntry("message", "connection refused");
    }

    @Test
    void probesTheTenantsTableWithoutTenantContext() throws Exception {
        DataSource dataSource = dataSourceReturningCount(0L);

        new PostgresRlsHealthIndicator(dataSource).health();

        Statement stmt = dataSource.getConnection().createStatement();
        // Tenant context must be cleared first, then the count must target a
        // table guaranteed non-empty in a working deployment — a probe that
        // filters on a fake tenant id returns 0 whether RLS is enforced or
        // bypassed and can never fail.
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute("RESET app.tenant_id");
        Mockito.verify(stmt, Mockito.atLeastOnce()).executeQuery(contains("FROM tenants"));
    }

    private static DataSource dataSourceReturningCount(long count) throws Exception {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        Statement stmt = Mockito.mock(Statement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(conn);
        Mockito.when(conn.createStatement()).thenReturn(stmt);
        Mockito.when(stmt.executeQuery(anyString())).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(true);
        Mockito.when(rs.getLong("c")).thenReturn(count);
        return dataSource;
    }
}
