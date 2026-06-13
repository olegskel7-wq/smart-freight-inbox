package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.2 S2 — proves the per-tenant settings chain end to end on a real database:
 * the override a tenant writes on /settings stays invisible to every other tenant
 * (Hibernate @TenantId for entity reads, the V20 RLS policy for native SQL), and
 * cross-tenant system work reads platform defaults only.
 *
 * Same shape as {@link TenantIsolationTest}: NOT @Transactional (Hibernate fixes the
 * session tenant at session open — one test-wide TX could never switch tenants),
 * cleanup is a hard DELETE under the system context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettingsTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;
    private static final String KEY = "ai.extraction-prompt";

    @Autowired
    private SettingsService settings;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        // Tenant administration is cross-tenant — declare the system state (S3 fail-loud).
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("settings-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void hardDeleteOverrides() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM tenant_setting");
            tenants.deleteById(secondTenant);
        });
    }

    @Test
    void override_isInvisibleToOtherTenants() {
        TenantContext.runAs(secondTenant, () -> settings.setString(KEY, "tenant-2 prompt"));

        String tenant2View = TenantContext.callAs(secondTenant, () -> settings.getString(KEY, "code default"));
        String tenant1View = TenantContext.callAs(DEFAULT_TENANT, () -> settings.getString(KEY, "code default"));

        assertThat(tenant2View).isEqualTo("tenant-2 prompt");
        // The default tenant still resolves the V9-seeded platform default, not the override.
        assertThat(tenant1View).contains("ISO 6346");
    }

    @Test
    void overwrite_updatesOwnRowOnly() {
        TenantContext.runAs(DEFAULT_TENANT, () -> settings.setString(KEY, "first"));
        TenantContext.runAs(secondTenant, () -> settings.setString(KEY, "other tenant"));
        TenantContext.runAs(DEFAULT_TENANT, () -> settings.setString(KEY, "second"));

        assertThat(TenantContext.callAs(DEFAULT_TENANT, () -> settings.getString(KEY, "d"))).isEqualTo("second");
        assertThat(TenantContext.callAs(secondTenant, () -> settings.getString(KEY, "d"))).isEqualTo("other tenant");

        Long total = TenantContext.callAsSystem(
                () -> jdbc.queryForObject("SELECT count(*) FROM tenant_setting", Long.class));
        assertThat(total).isEqualTo(2L);
    }

    @Test
    void systemContext_readsPlatformDefault() {
        TenantContext.runAs(secondTenant, () -> settings.setString("feature.sla-timer", "false"));

        // Schedulers and relay run cross-tenant: overrides must not bleed into them.
        boolean systemView = TenantContext.callAsSystem(
                () -> settings.isFeatureEnabled("feature.sla-timer", true));

        assertThat(systemView).isTrue();
    }

    @Test
    void nativeSql_isGuardedByRls() {
        TenantContext.runAs(secondTenant, () -> settings.setString(KEY, "secret tenant prompt"));

        // Raw SQL bypasses @TenantId — only the V20 policy stands between tenants.
        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> jdbc.queryForObject("SELECT count(*) FROM tenant_setting", Long.class));

        assertThat(visibleToTenant1).isZero();
    }
}
