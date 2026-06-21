package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.TestcontainersConfiguration;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.support.DefaultTenantTestExecutionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Phase 3.6 quote-to-booking conversion query against a real PostgreSQL
 * (Testcontainers). They pin down {@link QuoteFollowUpRepository#quoteConversionSince} — the
 * EXISTS-subquery booking match and the per-quote counting can't be unit-tested against a mock, and
 * the sender-based {@code LIKE} match over the raw {@code sender_email} needs the real SQL engine.
 *
 * <p>{@code @Transactional}: every test rolls back, nothing leaks into the shared context cache.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        value = DefaultTenantTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
class QuoteConversionIntegrationTest {

    @Autowired
    private QuoteFollowUpRepository quotes;

    @Autowired
    private EmailTicketRepository tickets;

    private static final LocalDateTime SINCE = LocalDateTime.now().minusDays(30);

    /** quote_follow_up has UNIQUE(tenant_id, ticket_id) — each sent quote needs a distinct ticket id. */
    private long nextTicketId = 1L;

    @BeforeEach
    void hideBaselineData() {
        tickets.deleteAll();
        quotes.deleteAll();
    }

    /** A sent quote: one quote_follow_up row anchored at quoteSentAt. */
    private void quote(String customerEmail, LocalDateTime quoteSentAt) {
        QuoteFollowUp q = new QuoteFollowUp();
        q.setTicketId(nextTicketId++);
        q.setCustomerEmail(customerEmail);
        q.setQuoteSentAt(quoteSentAt);
        q.setActive(true);
        quotes.save(q);
    }

    /** An inbound booking from a customer (raw "Name <addr>" sender form). */
    private void booking(String senderEmail, LocalDateTime createdAt) {
        ticket(senderEmail, createdAt, TicketType.BOOKING_CONFIRM, false);
    }

    private void ticket(String senderEmail, LocalDateTime createdAt, TicketType type, boolean proactive) {
        tickets.save(EmailTicket.builder()
                .messageId(UUID.randomUUID() + "@test")
                .senderEmail(senderEmail)
                .subject("conversion test")
                .body("test")
                .status(TicketStatus.RECEIVED)
                .ticketType(type)
                .proactive(proactive)
                .createdAt(createdAt)
                .build());
    }

    @Test
    void quotedThenBooked_countsAsConverted_quotedThenSilent_doesNot() {
        quote("alice@example.com", SINCE.plusDays(1));
        quote("bob@example.com", SINCE.plusDays(1));
        // Alice books after her quote (raw sender form, mixed case — match is case-insensitive).
        booking("Alice <Alice@Example.com>", SINCE.plusDays(2));

        QuoteConversion c = quotes.quoteConversionSince(SINCE);

        assertThat(c.getQuotes()).isEqualTo(2);
        assertThat(c.getConverted()).isEqualTo(1);
    }

    @Test
    void perQuoteCounting_sameCustomerQuotedTwiceBookedOnce_convertsBoth() {
        quote("carol@example.com", SINCE.plusDays(1));
        quote("carol@example.com", SINCE.plusDays(3));
        booking("carol@example.com", SINCE.plusDays(5));

        QuoteConversion c = quotes.quoteConversionSince(SINCE);

        // The unit is the quote: both quotes precede the booking, so both convert.
        assertThat(c.getQuotes()).isEqualTo(2);
        assertThat(c.getConverted()).isEqualTo(2);
    }

    @Test
    void bookingBeforeQuote_doesNotCount() {
        quote("dave@example.com", SINCE.plusDays(10));
        booking("dave@example.com", SINCE.plusDays(5));   // arrived before the quote went out

        QuoteConversion c = quotes.quoteConversionSince(SINCE);

        assertThat(c.getQuotes()).isEqualTo(1);
        assertThat(c.getConverted()).isZero();
    }

    @Test
    void onlyInboundBookingConfirmCounts_proactiveAndOtherTypesIgnored() {
        quote("erin@example.com", SINCE.plusDays(1));
        ticket("erin@example.com", SINCE.plusDays(2), TicketType.RFQ, false);          // wrong type
        ticket("erin@example.com", SINCE.plusDays(2), TicketType.BOOKING_CONFIRM, true); // our own outbound

        QuoteConversion c = quotes.quoteConversionSince(SINCE);

        assertThat(c.getQuotes()).isEqualTo(1);
        assertThat(c.getConverted()).isZero();
    }

    @Test
    void quoteOutsidePeriod_excludedFromDenominator() {
        quote("frank@example.com", SINCE.minusDays(5));   // sent before the window
        booking("frank@example.com", SINCE.plusDays(1));

        QuoteConversion c = quotes.quoteConversionSince(SINCE);

        assertThat(c.getQuotes()).isZero();
        assertThat(c.getConverted()).isZero();
    }
}
