package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.QuoteFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Access to proactive quote-follow-up subscriptions (Phase 2.5).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId} — except under the system context, where the
 * filter is off by design: {@code QuoteFollowUpScheduler} reads EVERY tenant's active follow-ups
 * in one pass, then processes each inside its owning tenant's scope.
 */
public interface QuoteFollowUpRepository extends JpaRepository<QuoteFollowUp, Long> {

    /** All live follow-ups. System context → across all tenants (the poller's first phase). */
    List<QuoteFollowUp> findByActiveTrue();

    /** Upsert lookup within the current tenant: re-sending a quote refreshes its existing follow-up. */
    Optional<QuoteFollowUp> findByTicketId(Long ticketId);

    /**
     * Quote-to-booking conversion for quotes sent in the period (Phase 3.6). Each sent RFQ quote is
     * a {@code quote_follow_up} row (armed at both send sites regardless of the follow-up flag); a
     * quote counts as converted when an inbound {@code BOOKING_CONFIRM} ticket from the same customer
     * arrived at or after the quote went out. This is the ROI metric "% of quotes that turned into
     * bookings".
     *
     * <p>Per-quote denominator: a customer quoted three times then booking once converts all three of
     * those quotes — the unit is the quote, not the customer. Matching is sender-based, mirroring
     * {@link EmailTicketRepository#existsInboundReplyFrom}: the cleaned {@code customer_email} stored
     * on the quote must appear within the booking's raw {@code sender_email}. Native SQL, so the
     * email-ticket soft-delete predicate is explicit (Hibernate's {@code @SQLRestriction} does not
     * apply to native queries); both tables are tenant-scoped by the V19/V24 RLS policies via the
     * connection GUC. A freshly-sent quote that has not yet converted lowers the rate — inherent to
     * any trailing-window conversion figure.
     *
     * @param since start of the reporting period (inclusive), measured on the quote-sent time
     * @return quotes sent and how many converted (both 0 when no quote went out in the period)
     */
    @Query(nativeQuery = true, value =
           "SELECT COUNT(*) AS quotes, " +
           "       COUNT(*) FILTER (WHERE EXISTS ( " +
           "           SELECT 1 FROM email_ticket b " +
           "            WHERE b.proactive = false " +
           "              AND b.deleted = false " +
           "              AND b.ticket_type = 'BOOKING_CONFIRM' " +
           "              AND b.created_at >= q.quote_sent_at " +
           "              AND LOWER(b.sender_email) LIKE CONCAT('%', LOWER(q.customer_email), '%') " +
           "       )) AS converted " +
           "  FROM quote_follow_up q " +
           " WHERE q.quote_sent_at >= :since")
    QuoteConversion quoteConversionSince(@Param("since") java.time.LocalDateTime since);
}
