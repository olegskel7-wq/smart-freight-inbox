package com.smartfreight.inbox.tenancy;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two tenant-isolation layers of Phase 2.2 actually isolate.
 *
 * Layer 1 — Hibernate {@code @TenantId}: entity queries are filtered by the session's
 * tenant, inserts are stamped with it.
 * Layer 2 — Postgres RLS (V19): policies on the table guard everything Hibernate cannot
 * see — native SQL (the analytics path!) and raw JDBC — and hold even when layer 1 is
 * bypassed entirely.
 *
 * Deliberately NOT {@code @Transactional}: Hibernate fixes the session's tenant when the
 * session opens, so one test-wide transaction could never switch tenants mid-test. Every
 * repository call here runs in its own short transaction under the ambient TenantContext;
 * cleanup is a hard DELETE under the system context instead of a rollback.
 *
 * These tests connect as {@code smartfreight_app} (see TestcontainersConfiguration) —
 * under the container's superuser they would be meaningless: RLS does not bind superusers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantIsolationTest {

    @Autowired
    private EmailTicketRepository tickets;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer<?> postgres;

    private static final long DEFAULT_TENANT = 1L;
    private Long secondTenant;

    @BeforeEach
    void createSecondTenant() {
        // The tenant table itself carries no RLS — any context can read/insert it.
        secondTenant = tenants.save(Tenant.builder().name("isolation-" + UUID.randomUUID()).build()).getId();
    }

    @AfterEach
    void hardDeleteEverything() {
        // EmailTicket uses soft delete via the entity — wipe through SQL instead, under
        // the system context so RLS lets us see every tenant's rows.
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM outbox_event");
            jdbc.update("DELETE FROM email_ticket");
        });
        tenants.deleteById(secondTenant);
    }

    private EmailTicket newTicket(String messageId) {
        return EmailTicket.builder()
                .messageId(messageId)
                .senderEmail("customer@example.com")
                .subject("isolation test")
                .body("test")
                .status(TicketStatus.RECEIVED)
                .build();
    }

    @Test
    void insert_stampsCurrentTenant() {
        EmailTicket saved = TenantContext.callAs(secondTenant,
                () -> tickets.save(newTicket("stamp@test")));

        assertThat(saved.getTenantId()).isEqualTo(secondTenant);
    }

    @Test
    void entityQueries_seeOnlyOwnTenant() {
        TenantContext.runAs(DEFAULT_TENANT, () -> tickets.save(newTicket("t1@test")));
        TenantContext.runAs(secondTenant, () -> tickets.save(newTicket("t2@test")));

        List<EmailTicket> tenant1View = TenantContext.callAs(DEFAULT_TENANT, tickets::findAll);

        assertThat(tenant1View)
                .extracting(EmailTicket::getMessageId)
                .containsExactly("t1@test");
    }

    @Test
    void findById_acrossTenants_readsAsNotFound() {
        Long foreignId = TenantContext.callAs(secondTenant,
                () -> tickets.save(newTicket("foreign@test"))).getId();

        assertThat(TenantContext.callAs(DEFAULT_TENANT, () -> tickets.findById(foreignId)))
                .isEmpty();
    }

    @Test
    void nativeSql_isGuardedByRls() {
        TenantContext.runAs(DEFAULT_TENANT, () -> tickets.save(newTicket("native-t1@test")));
        TenantContext.runAs(secondTenant, () -> tickets.save(newTicket("native-t2@test")));

        // Raw SQL — Hibernate's @TenantId filter cannot help here (the @SQLRestriction
        // lesson, tenant edition). Only the V19 policy stands between tenants.
        Long visibleToTenant1 = TenantContext.callAs(DEFAULT_TENANT,
                () -> jdbc.queryForObject("SELECT count(*) FROM email_ticket", Long.class));

        assertThat(visibleToTenant1).isEqualTo(1L);
    }

    @Test
    void systemContext_seesAllTenants() {
        TenantContext.runAs(DEFAULT_TENANT, () -> tickets.save(newTicket("sys-t1@test")));
        TenantContext.runAs(secondTenant, () -> tickets.save(newTicket("sys-t2@test")));

        Long visibleToSystem = TenantContext.callAsSystem(
                () -> jdbc.queryForObject("SELECT count(*) FROM email_ticket", Long.class));

        assertThat(visibleToSystem).isEqualTo(2L);
    }

    @Test
    void sameMessageId_isAllowedAcrossTenants() {
        // Two tenants can legitimately receive a copy of one email (CC) — dedup is
        // per-tenant since V18 (UNIQUE(tenant_id, message_id)).
        TenantContext.runAs(DEFAULT_TENANT, () -> tickets.save(newTicket("shared@test")));
        EmailTicket second = TenantContext.callAs(secondTenant,
                () -> tickets.save(newTicket("shared@test")));

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void rawConnectionWithoutGucs_seesNoRows() throws Exception {
        TenantContext.runAs(DEFAULT_TENANT, () -> tickets.save(newTicket("raw@test")));

        // Fresh connection as the app role, bypassing TenantAwareDataSource: no app.*
        // GUCs are set, so the policy evaluates to NULL → no rows. Fail closed. This
        // also proves smartfreight_app is genuinely subject to RLS (a superuser or
        // table owner would see the row regardless of any policy).
        try (Connection raw = DriverManager.getConnection(
                postgres.getJdbcUrl(), "smartfreight_app", "smartfreight_app");
             Statement st = raw.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM email_ticket")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }
}
