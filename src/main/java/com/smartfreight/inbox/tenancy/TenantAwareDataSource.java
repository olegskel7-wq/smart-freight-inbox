package com.smartfreight.inbox.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Feeds the current tenant into Postgres session GUCs for Row-Level Security (Phase 2.2).
 *
 * <p>Every connection checkout sets BOTH {@code app.tenant_id} and {@code app.is_system}
 * unconditionally — a pooled connection therefore can never carry a stale tenant from its
 * previous user, without needing a reset-on-return proxy. The V19 policies read these GUCs:
 * tenant state sees its own rows, system state sees everything, neither set sees nothing
 * (fail closed — though under S1 semantics an unset context resolves to the default tenant).
 *
 * <p>This wrapper covers the application pool only. Flyway connects separately as the
 * admin role and is intentionally not subject to RLS (DDL + backfills).
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private final TenancyProperties properties;

    public TenantAwareDataSource(DataSource delegate, TenancyProperties properties) {
        super(delegate);
        this.properties = properties;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        boolean system = TenantContext.isSystem();
        Long tenantId = TenantContext.tenantIdOrNull();
        String tenantValue = system
                ? ""
                : String.valueOf(tenantId != null ? tenantId : properties.getDefaultId());
        // set_config with is_local=false: survives for the whole session — the next
        // checkout overwrites it, and SET LOCAL would be useless outside a transaction.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, false), set_config('app.is_system', ?, false)")) {
            ps.setString(1, tenantValue);
            ps.setString(2, system ? "true" : "false");
            ps.execute();
        } catch (SQLException e) {
            // Never hand out a connection whose tenant GUCs are in an unknown state.
            connection.close();
            throw e;
        }
        return connection;
    }
}
