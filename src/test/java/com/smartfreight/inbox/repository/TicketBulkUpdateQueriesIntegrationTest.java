package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bulk JPQL statements of {@link EmailTicketRepository} against a real PostgreSQL
 * (Testcontainers, RLS on): which rows each one touches, which columns it writes, what it counts,
 * and that a tenant context cannot reach another tenant's rows. The schedulers that call them
 * ({@code StuckTicketRecovery}, {@code StuckTicketSweeper}, {@code SlaBreachScheduler},
 * {@code TicketProcessingService.prepareForRetry}, {@code QuoteFollowUpScheduler}) are unit-tested
 * over a mocked repository, so until now the SQL itself ran unchecked.
 *
 * <p>Not {@code @Transactional}: bulk updates bypass the persistence context, so every read here is a
 * fresh session or plain JDBC. Two tenants are created per test and hard-deleted afterwards under the
 * system context. Schedulers run under the system context and therefore across tenants, so the row
 * counts they get include whatever else is in the database; the exact-count assertions run under a
 * tenant context, where RLS leaves only that tenant's rows in reach.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketBulkUpdateQueriesIntegrationTest {

    @Autowired
    private EmailTicketRepository tickets;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDateTime AN_HOUR_AGO = NOW.minusHours(1);
    private static final LocalDateTime A_DAY_AGO = NOW.minusDays(1);

    private Long tenantA;
    private Long tenantB;

    @BeforeEach
    void createTenants() {
        tenantA = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("bulk-a-" + UUID.randomUUID()).build()).getId());
        tenantB = TenantContext.callAsSystem(
                () -> tenants.save(Tenant.builder().name("bulk-b-" + UUID.randomUUID()).build()).getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM email_ticket WHERE tenant_id IN (?, ?)", tenantA, tenantB);
            tenants.deleteById(tenantA);
            tenants.deleteById(tenantB);
        });
    }

    // ---------------------------------------------------------------- bulkTransitionStatus

    @Test
    void bulkTransitionStatus_movesEveryRowInTheFromStatus_andBumpsUpdatedAt() {
        Long processing = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING).updatedAt(A_DAY_AGO));
        Long draft = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).updatedAt(A_DAY_AGO));

        int moved = asTenant(tenantA, () -> tickets.bulkTransitionStatus(
                TicketStatus.PROCESSING, TicketStatus.RETRY_LATER));

        assertThat(moved).isEqualTo(1);
        assertThat(status(processing)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(updatedAt(processing)).isAfter(AN_HOUR_AGO);
        assertThat(status(draft)).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(updatedAt(draft)).isEqualTo(A_DAY_AGO);
    }

    @Test
    void bulkTransitionStatus_underTheSystemContext_reachesEveryTenant() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.PROCESSING));

        int moved = asSystem(() -> tickets.bulkTransitionStatus(
                TicketStatus.PROCESSING, TicketStatus.RETRY_LATER));

        assertThat(moved).isGreaterThanOrEqualTo(2);
        assertThat(status(a)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(b)).isEqualTo(TicketStatus.RETRY_LATER);
    }

    @Test
    void bulkTransitionStatus_underATenantContext_leavesOtherTenantsAlone() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.PROCESSING));

        int moved = asTenant(tenantA, () -> tickets.bulkTransitionStatus(
                TicketStatus.PROCESSING, TicketStatus.RETRY_LATER));

        assertThat(moved).isEqualTo(1);
        assertThat(status(a)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(b)).isEqualTo(TicketStatus.PROCESSING);
    }

    @Test
    void bulkTransitionStatus_skipsSoftDeletedRows() {
        Long live = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING));
        Long deleted = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING));
        softDelete(deleted);

        int moved = asTenant(tenantA, () -> tickets.bulkTransitionStatus(
                TicketStatus.PROCESSING, TicketStatus.RETRY_LATER));

        assertThat(moved).isEqualTo(1);
        assertThat(status(live)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(deleted)).isEqualTo(TicketStatus.PROCESSING);
    }

    // ----------------------------------------------------------- bulkTransitionStaleStatus

    @Test
    void bulkTransitionStaleStatus_movesOnlyListedStatusesOlderThanTheCutoff() {
        List<TicketStatus> sweepable = List.of(TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING);
        Long staleReceived = ticket(tenantA, t -> t.status(TicketStatus.RECEIVED).updatedAt(A_DAY_AGO));
        Long staleNew = ticket(tenantA, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO));
        Long staleProcessing = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING).updatedAt(A_DAY_AGO));
        Long freshProcessing = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING).updatedAt(NOW));
        Long staleDraft = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).updatedAt(A_DAY_AGO));
        Long onTheCutoff = ticket(tenantA, t -> t.status(TicketStatus.NEW).updatedAt(AN_HOUR_AGO));

        int swept = asTenant(tenantA, () -> tickets.bulkTransitionStaleStatus(
                sweepable, TicketStatus.RETRY_LATER, AN_HOUR_AGO));

        assertThat(swept).isEqualTo(3);
        assertThat(status(staleReceived)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(staleNew)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(staleProcessing)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(updatedAt(staleProcessing)).isAfter(AN_HOUR_AGO);
        assertThat(status(freshProcessing)).isEqualTo(TicketStatus.PROCESSING);
        assertThat(status(staleDraft)).isEqualTo(TicketStatus.DRAFT_READY);
        // strictly older than the cutoff: a row updated exactly at the cutoff is not stale yet
        assertThat(status(onTheCutoff)).isEqualTo(TicketStatus.NEW);
    }

    @Test
    void bulkTransitionStaleStatus_underATenantContext_leavesOtherTenantsAlone() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO));

        int swept = asTenant(tenantA, () -> tickets.bulkTransitionStaleStatus(
                List.of(TicketStatus.NEW), TicketStatus.RETRY_LATER, AN_HOUR_AGO));

        assertThat(swept).isEqualTo(1);
        assertThat(status(a)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(status(b)).isEqualTo(TicketStatus.NEW);
    }

    // ------------------------------------------------------------------- markSlaBreached

    @Test
    void markSlaBreached_flagsOverdueOpenTickets_withoutTouchingUpdatedAt() {
        List<TicketStatus> exempt = List.of(TicketStatus.CLOSED, TicketStatus.IRRELEVANT);
        Long overdue = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY)
                .slaDeadlineAt(AN_HOUR_AGO).updatedAt(A_DAY_AGO));
        Long notYetDue = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).slaDeadlineAt(NOW.plusHours(1)));
        Long noDeadline = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY));
        Long closed = ticket(tenantA, t -> t.status(TicketStatus.CLOSED).slaDeadlineAt(AN_HOUR_AGO));
        Long irrelevant = ticket(tenantA, t -> t.status(TicketStatus.IRRELEVANT).slaDeadlineAt(AN_HOUR_AGO));
        Long alreadyBreached = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY)
                .slaDeadlineAt(AN_HOUR_AGO).slaBreached(true));
        Long dueExactlyNow = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).slaDeadlineAt(NOW));

        int breached = asTenant(tenantA, () -> tickets.markSlaBreached(NOW, exempt));

        assertThat(breached).isEqualTo(1);
        assertThat(slaBreached(overdue)).isTrue();
        // the sweeper reads updatedAt as "last pipeline activity" — an SLA flag must not refresh it
        assertThat(updatedAt(overdue)).isEqualTo(A_DAY_AGO);
        assertThat(slaBreached(notYetDue)).isFalse();
        assertThat(slaBreached(noDeadline)).isFalse();
        assertThat(slaBreached(closed)).isFalse();
        assertThat(slaBreached(irrelevant)).isFalse();
        assertThat(slaBreached(alreadyBreached)).isTrue();
        // strictly before now: a deadline that is exactly now is not breached yet
        assertThat(slaBreached(dueExactlyNow)).isFalse();
    }

    @Test
    void markSlaBreached_underATenantContext_leavesOtherTenantsAlone() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.NEW).slaDeadlineAt(AN_HOUR_AGO));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.NEW).slaDeadlineAt(AN_HOUR_AGO));

        int breached = asTenant(tenantA, () -> tickets.markSlaBreached(NOW, List.of(TicketStatus.CLOSED)));

        assertThat(breached).isEqualTo(1);
        assertThat(slaBreached(a)).isTrue();
        assertThat(slaBreached(b)).isFalse();
    }

    // ------------------------------------------------------------------ resetFailedToNew

    @Test
    void resetFailedToNew_resetsStatusAndRetryCount_andBumpsUpdatedAt() {
        Long failed = ticket(tenantA, t -> t.status(TicketStatus.FAILED).retryCount(3).updatedAt(A_DAY_AGO));
        Long retryLater = ticket(tenantA, t -> t.status(TicketStatus.RETRY_LATER).retryCount(2).updatedAt(A_DAY_AGO));

        int reset = asTenant(tenantA, () -> tickets.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED));

        assertThat(reset).isEqualTo(1);
        assertThat(status(failed)).isEqualTo(TicketStatus.NEW);
        assertThat(retryCount(failed)).isZero();
        assertThat(updatedAt(failed)).isAfter(AN_HOUR_AGO);
        assertThat(status(retryLater)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(retryCount(retryLater)).isEqualTo(2);
    }

    // ------------------------------------------------------------- existsInboundReplyFrom

    @Test
    void existsInboundReplyFrom_matchesALaterInboundFromThatSender_caseInsensitively() {
        ticket(tenantA, t -> t.senderEmail("Jane.Doe@Customer.test").createdAt(NOW));

        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("jane.doe@customer.test", AN_HOUR_AGO)))
                .isTrue();
        // the query is a substring match on the lower-cased sender: a bare address inside a display-name form
        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("doe@customer", AN_HOUR_AGO)))
                .isTrue();
        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("someone.else@customer.test", AN_HOUR_AGO)))
                .isFalse();
    }

    @Test
    void existsInboundReplyFrom_ignoresProactiveMail_olderMail_andOtherTenants() {
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").createdAt(NOW).proactive(true));
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").createdAt(A_DAY_AGO));
        ticket(tenantB, t -> t.senderEmail("jane@customer.test").createdAt(NOW));

        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("jane@customer.test", AN_HOUR_AGO)))
                .isFalse();
        assertThat(asTenant(tenantB, () -> tickets.existsInboundReplyFrom("jane@customer.test", AN_HOUR_AGO)))
                .isTrue();
    }

    @Test
    void existsInboundReplyFrom_requiresMailStrictlyAfterSince() {
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").createdAt(AN_HOUR_AGO));

        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("jane@customer.test", AN_HOUR_AGO)))
                .isFalse();
        assertThat(asTenant(tenantA, () -> tickets.existsInboundReplyFrom("jane@customer.test", A_DAY_AGO)))
                .isTrue();
    }

    // ------------------------------------------------------------------------- helpers

    private Long ticket(Long tenant, Consumer<EmailTicket.EmailTicketBuilder> customise) {
        EmailTicket.EmailTicketBuilder builder = EmailTicket.builder()
                .messageId("<bulk-" + UUID.randomUUID() + "@test>")
                .senderEmail("customer@acme.test")
                .subject("bulk query test")
                .body("test")
                .createdAt(NOW)
                .updatedAt(NOW);
        customise.accept(builder);
        return TenantContext.callAs(tenant, () -> tickets.save(builder.build()).getId());
    }

    /** Runs a bulk statement the way the schedulers do: one transaction, inside a tenant context. */
    private <T> T asTenant(Long tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> tx.execute(status -> work.get()));
    }

    private <T> T asSystem(Supplier<T> work) {
        return TenantContext.callAsSystem(() -> tx.execute(status -> work.get()));
    }

    private void softDelete(Long id) {
        TenantContext.runAsSystem(() -> jdbc.update("UPDATE email_ticket SET deleted = true WHERE id = ?", id));
    }

    // Reads go through JDBC under the system context: they must see soft-deleted rows and rows of
    // every tenant, and must not be served from a persistence context the bulk statement bypassed.
    private TicketStatus status(Long id) {
        return TicketStatus.valueOf(TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT status FROM email_ticket WHERE id = ?", String.class, id)));
    }

    private LocalDateTime updatedAt(Long id) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT updated_at FROM email_ticket WHERE id = ?", LocalDateTime.class, id));
    }

    private boolean slaBreached(Long id) {
        return Boolean.TRUE.equals(TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT sla_breached FROM email_ticket WHERE id = ?", Boolean.class, id)));
    }

    private int retryCount(Long id) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT retry_count FROM email_ticket WHERE id = ?", Integer.class, id));
    }
}
