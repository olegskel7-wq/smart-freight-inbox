package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TicketAuditLog;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.support.DefaultTenantTestExecutionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for the Phase 2.1 analytics queries against a real PostgreSQL
 * (Testcontainers). The native queries (daily volume, confidence histogram, average
 * first-response time) cannot be unit-tested: they rely on Postgres SQL (FILTER,
 * EXTRACT(EPOCH ...)) and must repeat the soft-delete predicate that Hibernate's
 * {@code @SQLRestriction} adds to JPQL but NOT to native SQL — exactly the behaviour
 * these tests pin down.
 *
 * {@code @Transactional}: every test rolls back, nothing leaks into the shared
 * Spring-context cache other tests reuse.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        value = DefaultTenantTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
class AnalyticsQueriesIntegrationTest {

    @Autowired
    private EmailTicketRepository tickets;

    @Autowired
    private TicketAuditLogRepository auditLog;

    private static final LocalDateTime SINCE = LocalDateTime.now().minusDays(7);

    /**
     * The Spring context (and its Postgres container) is cached across test classes, and
     * some of them COMMIT tickets (e.g. the pipeline E2E test). Soft-deleting everything
     * up front hides that baseline from both the JPQL aggregations (@SQLRestriction) and
     * the native ones (explicit deleted = false) — and rolls back with the test TX.
     */
    @BeforeEach
    void hideBaselineData() {
        auditLog.deleteAll();
        tickets.deleteAll();
    }

    private EmailTicket ticket(LocalDateTime createdAt, TicketStatus status,
                               TicketType type, Double confidence, boolean deleted) {
        return tickets.save(EmailTicket.builder()
                .messageId(UUID.randomUUID() + "@test")
                .senderEmail("customer@example.com")
                .subject("analytics test")
                .body("test")
                .status(status)
                .ticketType(type)
                .aiConfidence(confidence)
                .deleted(deleted)
                .createdAt(createdAt)
                .build());
    }

    private void audit(Long ticketId, String action, LocalDateTime at) {
        auditLog.save(TicketAuditLog.builder()
                .ticketId(ticketId)
                .actorEmail("operator@test")
                .action(action)
                .createdAt(at)
                .build());
    }

    @Test
    void dailyVolumeSince_splitsJunkAndSkipsDeleted() {
        LocalDateTime today = LocalDateTime.now();
        ticket(today, TicketStatus.DRAFT_READY, TicketType.RFQ, null, false);
        ticket(today, TicketStatus.IRRELEVANT, null, null, false);
        ticket(today, TicketStatus.DRAFT_READY, null, null, true);            // deleted — invisible
        ticket(today.minusDays(2), TicketStatus.CLOSED, TicketType.GENERAL, null, false);

        List<DailyVolume> rows = tickets.dailyVolumeSince(SINCE);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getDay()).isEqualTo(today.minusDays(2).toLocalDate());
        assertThat(rows.get(0).getTotal()).isEqualTo(1);
        assertThat(rows.get(0).getJunk()).isZero();
        assertThat(rows.get(1).getDay()).isEqualTo(LocalDate.now());
        assertThat(rows.get(1).getTotal()).isEqualTo(2);                      // deleted not counted
        assertThat(rows.get(1).getJunk()).isEqualTo(1);
    }

    @Test
    void confidenceHistogramSince_bucketsValuesAndFoldsExactOneIntoTopBucket() {
        LocalDateTime now = LocalDateTime.now();
        ticket(now, TicketStatus.DRAFT_READY, TicketType.RFQ, 0.05, false);   // bucket 0
        ticket(now, TicketStatus.DRAFT_READY, TicketType.RFQ, 0.85, false);   // bucket 8
        ticket(now, TicketStatus.AUTO_SENT, TicketType.STATUS_UPDATE, 1.0, false); // folds into 9
        ticket(now, TicketStatus.DRAFT_READY, TicketType.RFQ, 0.95, false);   // bucket 9
        ticket(now, TicketStatus.RECEIVED, null, null, false);                // no confidence — excluded
        ticket(now, TicketStatus.DRAFT_READY, TicketType.RFQ, 0.85, true);    // deleted — excluded

        List<ConfidenceBucket> rows = tickets.confidenceHistogramSince(SINCE);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getBucket()).isZero();
        assertThat(rows.get(0).getCount()).isEqualTo(1);
        assertThat(rows.get(1).getBucket()).isEqualTo(8);
        assertThat(rows.get(1).getCount()).isEqualTo(1);
        assertThat(rows.get(2).getBucket()).isEqualTo(9);
        assertThat(rows.get(2).getCount()).isEqualTo(2);
    }

    @Test
    void countByTypeSince_excludesJunkAndGroupsNullType() {
        LocalDateTime now = LocalDateTime.now();
        ticket(now, TicketStatus.DRAFT_READY, TicketType.RFQ, null, false);
        ticket(now, TicketStatus.CLOSED, TicketType.RFQ, null, false);
        ticket(now, TicketStatus.RECEIVED, null, null, false);                // untyped, relevant
        ticket(now, TicketStatus.IRRELEVANT, null, null, false);              // junk — excluded

        List<TypeCount> rows = tickets.countByTypeSince(SINCE, TicketStatus.IRRELEVANT);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.type()).isEqualTo(TicketType.RFQ);
            assertThat(r.count()).isEqualTo(2L);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.type()).isNull();
            assertThat(r.count()).isEqualTo(1L);
        });
    }

    @Test
    void avgFirstResponseSeconds_usesFirstSendPerTicketAttributedToSendDate() {
        LocalDateTime now = LocalDateTime.now();

        // Answered twice — only the FIRST send (60 s) counts.
        EmailTicket twice = ticket(now.minusHours(1), TicketStatus.CLOSED, TicketType.RFQ, null, false);
        audit(twice.getId(), TicketAuditService.ACTION_SEND, now.minusHours(1).plusSeconds(60));
        audit(twice.getId(), TicketAuditService.ACTION_SEND, now.minusHours(1).plusSeconds(600));

        // Auto-sent 180 s after receipt.
        EmailTicket auto = ticket(now.minusHours(2), TicketStatus.AUTO_SENT, TicketType.STATUS_UPDATE, null, false);
        audit(auto.getId(), TicketAuditService.ACTION_AUTO_SEND, now.minusHours(2).plusSeconds(180));

        // First answered BEFORE the period — excluded even though the ticket still exists.
        EmailTicket old = ticket(now.minusDays(30), TicketStatus.CLOSED, TicketType.RFQ, null, false);
        audit(old.getId(), TicketAuditService.ACTION_SEND, now.minusDays(20));

        // Non-send audit actions never count as replies.
        EmailTicket closedOnly = ticket(now.minusHours(3), TicketStatus.CLOSED, null, null, false);
        audit(closedOnly.getId(), TicketAuditService.ACTION_CLOSE, now.minusHours(2));

        Double avg = auditLog.avgFirstResponseSeconds(SINCE);

        assertThat(avg).isNotNull();
        assertThat(avg).isCloseTo((60 + 180) / 2.0, within(1.0));
    }

    @Test
    void avgFirstResponseSeconds_nullWhenNothingAnswered() {
        ticket(LocalDateTime.now(), TicketStatus.DRAFT_READY, TicketType.RFQ, null, false);

        assertThat(auditLog.avgFirstResponseSeconds(SINCE)).isNull();
    }
}
