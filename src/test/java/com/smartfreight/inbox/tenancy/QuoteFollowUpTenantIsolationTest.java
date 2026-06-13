package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.QuoteFollowUpRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.5 — proves quote_follow_up isolation on a real database: a follow-up one tenant records
 * is invisible to every other tenant through both layers (Hibernate {@code @TenantId} for entity
 * reads, the V24 RLS policy for native SQL), while the system context (the poller's phase A) sees
 * across all tenants.
 *
 * <p>Same shape as {@code ShipmentWatchTenantIsolationTest}: NOT {@code @Transactional} (Hibernate
 * fixes the session tenant at session open), cleanup is a hard DELETE under the system context.
 * Assertions are scoped by customer_email so unrelated rows from cached-context tests cannot affect them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QuoteFollowUpTenantIsolationTest {

    private static final long DEFAULT_TENANT = 1L;

    @Autowired
    private QuoteFollowUpRepository followUps;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        secondTenant = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("followup-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM quote_follow_up WHERE customer_email LIKE 'test-%@iso.test'");
            tenants.deleteById(secondTenant);
        });
    }

    private void createFollowUp(Long tenant, String email) {
        TenantContext.runAs(tenant, () -> {
            QuoteFollowUp f = new QuoteFollowUp();
            f.setTicketId(999L);
            f.setCustomerEmail(email);
            f.setQuoteSentAt(LocalDateTime.now());
            f.setActive(true);
            followUps.save(f);
        });
    }

    @Test
    void followUp_isInvisibleToOtherTenants_viaEntityQuery() {
        createFollowUp(secondTenant, "test-aaaa@iso.test");

        List<String> tenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> followUps.findByActiveTrue().stream().map(QuoteFollowUp::getCustomerEmail).toList());
        List<String> tenant2 = TenantContext.callAs(secondTenant,
                () -> followUps.findByActiveTrue().stream().map(QuoteFollowUp::getCustomerEmail).toList());

        assertThat(tenant1).doesNotContain("test-aaaa@iso.test");
        assertThat(tenant2).contains("test-aaaa@iso.test");
    }

    @Test
    void nativeSql_isGuardedByRls() {
        createFollowUp(secondTenant, "test-bbbb@iso.test");

        // Raw SQL bypasses @TenantId — only the V24 policy stands between tenants.
        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM quote_follow_up WHERE customer_email = ?", Long.class, "test-bbbb@iso.test"));
        Long visibleToSystem = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM quote_follow_up WHERE customer_email = ?", Long.class, "test-bbbb@iso.test"));

        assertThat(visibleToTenant1).isZero();
        assertThat(visibleToSystem).isEqualTo(1L);
    }

    @Test
    void systemContext_seesAllTenants() {
        createFollowUp(secondTenant, "test-cccc@iso.test");
        createFollowUp(DEFAULT_TENANT, "test-dddd@iso.test");

        List<String> systemView = TenantContext.callAsSystem(
                () -> followUps.findByActiveTrue().stream().map(QuoteFollowUp::getCustomerEmail).toList());

        assertThat(systemView).contains("test-cccc@iso.test", "test-dddd@iso.test");
    }
}
