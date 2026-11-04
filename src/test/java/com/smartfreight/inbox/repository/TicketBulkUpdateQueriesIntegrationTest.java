package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.scheduler.SlaBreachScheduler;
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
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the bulk JPQL statements of {@link EmailTicketRepository} against a real PostgreSQL
 * (Testcontainers, RLS on): which rows each one touches, which columns it writes, what it counts,
 * that soft-deleted rows stay out, and that a tenant context cannot reach another tenant's rows. The
 * callers ({@code StuckTicketRecovery}, {@code StuckTicketSweeper}, {@code SlaBreachScheduler},
 * {@code TicketProcessingService.prepareForRetry}, {@code QuoteFollowUpScheduler}) are unit-tested
 * over a mocked repository, so these tests are the only ones that run the statements themselves.
 *
 * <p>Not {@code @Transactional}: the rows must be committed for RLS and the bulk statements to see
 * them the way production does. Two tenants are created per test and hard-deleted afterwards.
 *
 * <p>The real schedulers keep running in this shared Spring context, across all tenants, so the test
 * data stays out of their reach: see {@link #NOW} and {@link #RETRIES_EXHAUSTED}. Exact counts are
 * asserted under a tenant context, where RLS leaves only that tenant's rows. The system context
 * reaches the whole shared database, so those checks roll back ({@link #asSystemThenRollBack}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketBulkUpdateQueriesIntegrationTest {

    /**
     * The test's "now", a week ahead of the wall clock. Every date here derives from it, so to the live
     * {@code SlaBreachScheduler} no deadline is overdue and to the {@code StuckTicketSweeper} no row is
     * stale; only the explicit arguments in these tests make them so.
     */
    private static final LocalDateTime NOW = LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDateTime AN_HOUR_AGO = NOW.minusHours(1);
    private static final LocalDateTime A_DAY_AGO = NOW.minusDays(1);

    /** Far above any retry limit: the live {@code RetryScheduler} picks up RETRY_LATER rows only below its limit. */
    private static final int RETRIES_EXHAUSTED = 1_000;

    @Autowired
    private EmailTicketRepository tickets;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

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
        assertUpdatedAtIsTheWallClock(processing);
        assertThat(status(draft)).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(updatedAt(draft)).isEqualTo(A_DAY_AGO);
    }

    @Test
    void bulkTransitionStatus_underTheSystemContext_reachesEveryTenant() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.PROCESSING));

        asSystemThenRollBack(() -> {
            tickets.bulkTransitionStatus(TicketStatus.PROCESSING, TicketStatus.RETRY_LATER);

            assertThat(status(a)).isEqualTo(TicketStatus.RETRY_LATER);
            assertThat(status(b)).isEqualTo(TicketStatus.RETRY_LATER);
        });

        assertThat(status(a)).isEqualTo(TicketStatus.PROCESSING);
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
        Long deleted = ticket(tenantA, t -> t.status(TicketStatus.PROCESSING).deleted(true));

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
        assertUpdatedAtIsTheWallClock(staleProcessing);
        assertThat(status(freshProcessing)).isEqualTo(TicketStatus.PROCESSING);
        assertThat(status(staleDraft)).isEqualTo(TicketStatus.DRAFT_READY);
        // strictly older than the cutoff: a row updated exactly at the cutoff is not stale yet
        assertThat(status(onTheCutoff)).isEqualTo(TicketStatus.NEW);
    }

    @Test
    void bulkTransitionStaleStatus_underTheSystemContext_reachesEveryTenant() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO));

        asSystemThenRollBack(() -> {
            tickets.bulkTransitionStaleStatus(List.of(TicketStatus.NEW), TicketStatus.RETRY_LATER, AN_HOUR_AGO);

            assertThat(status(a)).isEqualTo(TicketStatus.RETRY_LATER);
            assertThat(status(b)).isEqualTo(TicketStatus.RETRY_LATER);
        });

        assertThat(status(a)).isEqualTo(TicketStatus.NEW);
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

    @Test
    void bulkTransitionStaleStatus_skipsSoftDeletedRows() {
        Long deleted = ticket(tenantA, t -> t.status(TicketStatus.NEW).updatedAt(A_DAY_AGO).deleted(true));

        int swept = asTenant(tenantA, () -> tickets.bulkTransitionStaleStatus(
                List.of(TicketStatus.NEW), TicketStatus.RETRY_LATER, AN_HOUR_AGO));

        assertThat(swept).isZero();
        assertThat(status(deleted)).isEqualTo(TicketStatus.NEW);
    }

    // ------------------------------------------------------------------- markSlaBreached

    @Test
    void markSlaBreached_flagsOverdueOpenTickets_withoutTouchingUpdatedAt() {
        Long overdue = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY)
                .slaDeadlineAt(AN_HOUR_AGO).updatedAt(A_DAY_AGO));
        Long notYetDue = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).slaDeadlineAt(NOW.plusHours(1)));
        Long noDeadline = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY));
        Long closed = ticket(tenantA, t -> t.status(TicketStatus.CLOSED).slaDeadlineAt(AN_HOUR_AGO));
        Long irrelevant = ticket(tenantA, t -> t.status(TicketStatus.IRRELEVANT).slaDeadlineAt(AN_HOUR_AGO));
        Long autoSent = ticket(tenantA, t -> t.status(TicketStatus.AUTO_SENT).slaDeadlineAt(AN_HOUR_AGO));
        Long alreadyBreached = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY)
                .slaDeadlineAt(AN_HOUR_AGO).slaBreached(true));
        Long dueExactlyNow = ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).slaDeadlineAt(NOW));

        int breached = asTenant(tenantA, () -> tickets.markSlaBreached(NOW, SlaBreachScheduler.SLA_EXEMPT_STATUSES));

        assertThat(breached).isEqualTo(1);
        assertThat(slaBreached(overdue)).isTrue();
        // the sweeper reads updatedAt as "last pipeline activity" — an SLA flag must not refresh it
        assertThat(updatedAt(overdue)).isEqualTo(A_DAY_AGO);
        assertThat(slaBreached(notYetDue)).isFalse();
        assertThat(slaBreached(noDeadline)).isFalse();
        assertThat(slaBreached(closed)).isFalse();
        assertThat(slaBreached(irrelevant)).isFalse();
        assertThat(slaBreached(autoSent)).isFalse();
        assertThat(slaBreached(alreadyBreached)).isTrue();
        // strictly before now: a deadline that is exactly now is not breached yet
        assertThat(slaBreached(dueExactlyNow)).isFalse();
    }

    /**
     * What the analytics SLA KPI reads. A ticket auto-sent in time stays out of it once its deadline passes;
     * one flagged while it waited (Ollama down, say) and only then auto-sent stays in, because it was late.
     */
    @Test
    void markSlaBreached_analyticsCountsOnlyTicketsThatWereLate() {
        ticket(tenantA, t -> t.status(TicketStatus.AUTO_SENT).slaDeadlineAt(AN_HOUR_AGO));
        ticket(tenantA, t -> t.status(TicketStatus.AUTO_SENT).slaDeadlineAt(AN_HOUR_AGO).slaBreached(true));
        ticket(tenantA, t -> t.status(TicketStatus.DRAFT_READY).slaDeadlineAt(AN_HOUR_AGO));

        long count = asTenant(tenantA, () -> {
            tickets.markSlaBreached(NOW, SlaBreachScheduler.SLA_EXEMPT_STATUSES);
            return tickets.countByCreatedAtGreaterThanEqualAndSlaBreachedTrue(A_DAY_AGO);
        });

        assertThat(count).isEqualTo(2);
    }

    @Test
    void markSlaBreached_underTheSystemContext_reachesEveryTenant() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.NEW).slaDeadlineAt(AN_HOUR_AGO));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.NEW).slaDeadlineAt(AN_HOUR_AGO));

        asSystemThenRollBack(() -> {
            tickets.markSlaBreached(NOW, List.of(TicketStatus.CLOSED));

            assertThat(slaBreached(a)).isTrue();
            assertThat(slaBreached(b)).isTrue();
        });

        assertThat(slaBreached(a)).isFalse();
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

    @Test
    void markSlaBreached_skipsSoftDeletedRows() {
        Long deleted = ticket(tenantA, t -> t.status(TicketStatus.NEW).slaDeadlineAt(AN_HOUR_AGO).deleted(true));

        int breached = asTenant(tenantA, () -> tickets.markSlaBreached(NOW, List.of(TicketStatus.CLOSED)));

        assertThat(breached).isZero();
        assertThat(slaBreached(deleted)).isFalse();
    }

    // ------------------------------------------------------------------ resetFailedToNew

    @Test
    void resetFailedToNew_resetsStatusAndRetryCount_andBumpsUpdatedAt() {
        Long failed = ticket(tenantA, t -> t.status(TicketStatus.FAILED).updatedAt(A_DAY_AGO));
        Long retryLater = ticket(tenantA, t -> t.status(TicketStatus.RETRY_LATER).updatedAt(A_DAY_AGO));

        int reset = asTenant(tenantA, () -> tickets.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED));

        assertThat(reset).isEqualTo(1);
        assertThat(status(failed)).isEqualTo(TicketStatus.NEW);
        assertThat(retryCount(failed)).isZero();
        assertUpdatedAtIsTheWallClock(failed);
        assertThat(status(retryLater)).isEqualTo(TicketStatus.RETRY_LATER);
        assertThat(retryCount(retryLater)).isEqualTo(RETRIES_EXHAUSTED);
        assertThat(updatedAt(retryLater)).isEqualTo(A_DAY_AGO);
    }

    @Test
    void resetFailedToNew_underATenantContext_leavesOtherTenantsAlone() {
        Long a = ticket(tenantA, t -> t.status(TicketStatus.FAILED));
        Long b = ticket(tenantB, t -> t.status(TicketStatus.FAILED));

        int reset = asTenant(tenantA, () -> tickets.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED));

        assertThat(reset).isEqualTo(1);
        assertThat(status(a)).isEqualTo(TicketStatus.NEW);
        assertThat(status(b)).isEqualTo(TicketStatus.FAILED);
    }

    @Test
    void resetFailedToNew_skipsSoftDeletedRows() {
        Long deleted = ticket(tenantA, t -> t.status(TicketStatus.FAILED).deleted(true));

        int reset = asTenant(tenantA, () -> tickets.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED));

        assertThat(reset).isZero();
        assertThat(status(deleted)).isEqualTo(TicketStatus.FAILED);
    }

    // ------------------------------------------------------------- existsInboundReplyFrom

    @Test
    void existsInboundReplyFrom_matchesALaterInboundFromThatSender_caseInsensitively() {
        ticket(tenantA, t -> t.senderEmail("Jane.Doe@Customer.test"));

        assertThat(replyExists(tenantA, "jane.doe@customer.test", AN_HOUR_AGO)).isTrue();
        assertThat(replyExists(tenantA, "someone.else@customer.test", AN_HOUR_AGO)).isFalse();
    }

    @Test
    void existsInboundReplyFrom_findsTheAddressInsideADisplayNameForm() {
        ticket(tenantA, t -> t.senderEmail("Jane Doe <jane.doe@customer.test>"));

        assertThat(replyExists(tenantA, "jane.doe@customer.test", AN_HOUR_AGO)).isTrue();
    }

    /**
     * Records a known limitation, not a wish (docs/refactoring.md, "Found while writing the safety net"):
     * the match is a plain substring, so a different customer whose address ends the same way counts as
     * the reply and the follow-up is dropped. It fails toward not nudging.
     */
    @Test
    void existsInboundReplyFrom_alsoMatchesALongerAddressEndingTheSameWay() {
        ticket(tenantA, t -> t.senderEmail("jan@customer.test"));

        assertThat(replyExists(tenantA, "an@customer.test", AN_HOUR_AGO)).isTrue();
    }

    @Test
    void existsInboundReplyFrom_ignoresProactiveMail_olderMail_andOtherTenants() {
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").proactive(true));
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").createdAt(A_DAY_AGO));
        ticket(tenantB, t -> t.senderEmail("jane@customer.test"));

        assertThat(replyExists(tenantA, "jane@customer.test", AN_HOUR_AGO)).isFalse();
        assertThat(replyExists(tenantB, "jane@customer.test", AN_HOUR_AGO)).isTrue();
    }

    @Test
    void existsInboundReplyFrom_ignoresSoftDeletedMail() {
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").deleted(true));

        assertThat(replyExists(tenantA, "jane@customer.test", AN_HOUR_AGO)).isFalse();
    }

    @Test
    void existsInboundReplyFrom_requiresMailStrictlyAfterSince() {
        ticket(tenantA, t -> t.senderEmail("jane@customer.test").createdAt(AN_HOUR_AGO));

        assertThat(replyExists(tenantA, "jane@customer.test", AN_HOUR_AGO)).isFalse();
        assertThat(replyExists(tenantA, "jane@customer.test", A_DAY_AGO)).isTrue();
    }

    // ------------------------------------------------------------------------- helpers

    private Long ticket(Long tenant, Consumer<EmailTicket.EmailTicketBuilder> customise) {
        EmailTicket.EmailTicketBuilder builder = EmailTicket.builder()
                .messageId("<bulk-" + UUID.randomUUID() + "@test>")
                .senderEmail("customer@acme.test")
                .subject("bulk query test")
                .body("test")
                .retryCount(RETRIES_EXHAUSTED)
                .createdAt(NOW)
                .updatedAt(NOW);
        customise.accept(builder);
        return TenantContext.callAs(tenant, () -> tickets.save(builder.build()).getId());
    }

    private boolean replyExists(Long tenant, String email, LocalDateTime since) {
        return asTenant(tenant, () -> tickets.existsInboundReplyFrom(email, since));
    }

    /** One transaction inside a tenant context; the checks after it read what was committed. */
    private <T> T asTenant(Long tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> tx.execute(status -> work.get()));
    }

    /**
     * The context the schedulers run under. A statement here reaches every row of the shared database,
     * so the statement and its checks share one transaction that never commits.
     */
    private void asSystemThenRollBack(Runnable statementAndChecks) {
        TenantContext.runAsSystem(() -> tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            statementAndChecks.run();
        }));
    }

    /** The statements write the database clock, far behind every date the rows were created with. */
    private void assertUpdatedAtIsTheWallClock(Long id) {
        assertThat(updatedAt(id)).isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    private TicketStatus status(Long id) {
        return TicketStatus.valueOf(column(id, "status", String.class));
    }

    private LocalDateTime updatedAt(Long id) {
        return column(id, "updated_at", LocalDateTime.class);
    }

    private boolean slaBreached(Long id) {
        return column(id, "sla_breached", Boolean.class);
    }

    private int retryCount(Long id) {
        return column(id, "retry_count", Integer.class);
    }

    /**
     * Plain JDBC under the system context: it sees soft-deleted rows and every tenant's rows, and is
     * never served from a persistence context the bulk statement bypassed.
     */
    private <T> T column(Long id, String name, Class<T> type) {
        return TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT " + name + " FROM email_ticket WHERE id = ?", type, id));
    }
}
