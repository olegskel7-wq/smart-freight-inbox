package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.ShipmentWatch;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.4 — proves shipment_watch isolation on a real database: a watch one tenant records is
 * invisible to every other tenant through both layers (Hibernate {@code @TenantId} for entity
 * reads, the V22 RLS policy for native SQL), while the system context (the poller's phase A) sees
 * across all tenants.
 *
 * <p>Same shape as {@link SettingsTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context.
 * Assertions are scoped by identifier so unrelated rows from cached-context tests cannot affect them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShipmentWatchTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private ShipmentWatchRepository watches;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("watch-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM shipment_watch WHERE identifier LIKE 'TEST-%'");
            tenants.deleteById(secondTenant);
        });
    }

    private void createWatch(Long tenant, String identifier) {
        TenantContext.runAs(tenant, () -> {
            ShipmentWatch w = new ShipmentWatch();
            w.setIdentifier(identifier);
            w.setCustomerEmail("customer@acme.com");
            w.setLastNotifiedStatus("AT_PORT");
            w.setActive(true);
            watches.save(w);
        });
    }

    @Test
    void watch_isInvisibleToOtherTenants_viaEntityQuery() {
        createWatch(secondTenant, "TEST-AAAA");

        List<String> tenant1Ids = TenantContext.callAs(DEFAULT_TENANT,
                () -> watches.findByActiveTrue().stream().map(ShipmentWatch::getIdentifier).toList());
        List<String> tenant2Ids = TenantContext.callAs(secondTenant,
                () -> watches.findByActiveTrue().stream().map(ShipmentWatch::getIdentifier).toList());

        assertThat(tenant1Ids).doesNotContain("TEST-AAAA");
        assertThat(tenant2Ids).contains("TEST-AAAA");
    }

    @Test
    void nativeSql_isGuardedByRls() {
        createWatch(secondTenant, "TEST-BBBB");

        // Raw SQL bypasses @TenantId — only the V22 policy stands between tenants.
        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM shipment_watch WHERE identifier = ?", Long.class, "TEST-BBBB"));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shipment_watch WHERE identifier = ?", Long.class, "TEST-BBBB"));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void systemContext_seesAllTenants() {
        createWatch(secondTenant, "TEST-CCCC");
        createWatch(DEFAULT_TENANT, "TEST-DDDD");

        List<String> systemView = TenantContext.callAsSystem(
                () -> watches.findByActiveTrue().stream().map(ShipmentWatch::getIdentifier).toList());

        assertThat(systemView).contains("TEST-CCCC", "TEST-DDDD");
    }
}
