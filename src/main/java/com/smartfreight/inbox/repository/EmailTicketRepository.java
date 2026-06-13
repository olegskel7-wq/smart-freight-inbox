package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link EmailTicket} entity.
 *
 * The inherited {@link JpaRepository} provides ready-made CRUD methods.
 * Additional methods are defined using Spring Data method naming conventions
 * or JPQL queries (@Query).
 */
@Repository
public interface EmailTicketRepository extends JpaRepository<EmailTicket, Long> {

    /**
     * Finds a ticket by the unique message identifier (Message-ID header).
     * Used for duplicate detection: has this message already been saved?
     *
     * @param messageId value of the email Message-ID header
     * @return the ticket if found
     */
    Optional<EmailTicket> findByMessageId(String messageId);

    /**
     * Paginated ticket list with optional status filter.
     * Used by the operator UI — avoids loading all tickets into memory.
     *
     * @param status   the status to search for
     * @param pageable page and sort specification
     * @return page of tickets
     */
    Page<EmailTicket> findByStatus(TicketStatus status, Pageable pageable);

    /**
     * Counts how many tickets exist with the specified status.
     * Used for metrics export (Gauge).
     *
     * @param status the status to search for
     * @return ticket count
     */
    long countByStatus(TicketStatus status);

    /**
     * Finds RETRY_LATER tickets that have not exceeded the retry limit.
     * RetryScheduler uses this to find tickets eligible for reprocessing.
     * The maxRetries parameter protects against infinite retry loops.
     *
     * Paginated (Phase 1.8): the scheduler only ever re-enqueues one batch per run,
     * so the batch limit belongs in the SQL (LIMIT) — not in a subList over fully
     * loaded entities with their TEXT columns (body/draft_reply up to 10 MB each).
     *
     * @param status     the status to search for (typically RETRY_LATER)
     * @param maxRetries maximum retryCount (exclusive upper bound)
     * @param pageable   batch size and ordering (oldest updatedAt first = FIFO fairness)
     * @return one page of tickets eligible for retry; totalElements = full eligible count
     */
    Page<EmailTicket> findByStatusAndRetryCountLessThan(TicketStatus status, int maxRetries, Pageable pageable);

    /**
     * Returns ONLY ticket IDs for the specified statuses (projection).
     *
     * Used by {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}
     * to build the work list without loading full entities into memory. Benefits:
     * — heavy TEXT fields body/draft_reply (up to 10 MB each) are not loaded;
     * — no managed entities are created in the Persistence Context unnecessarily;
     * — the subsequent process(id) will reload the ticket within its own transaction.
     *
     * @param statuses collection of statuses to search for (e.g. NEW, RETRY_LATER)
     * @return list of IDs sorted in ascending order (for reproducibility)
     */
    @Query("SELECT t.id FROM EmailTicket t WHERE t.status IN :statuses ORDER BY t.id")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<TicketStatus> statuses);

    /**
     * Bulk reset of all FAILED tickets to NEW status (single SQL operation).
     *
     * Used by {@link com.smartfreight.inbox.controller.AdminController#reprocessAll}
     * before starting the pipeline: FAILED tickets are marked as NEW and
     * retry_count is zeroed so that TicketProcessingService.process() accepts them.
     *
     * Advantages of bulk update over findAll + setStatus + save:
     * — 1 SQL UPDATE vs N SELECT + N UPDATE;
     * — entities are not loaded into the Persistence Context (memory + GC);
     * — short transaction, HikariCP connection is released immediately.
     *
     * Warning: bulk update does NOT synchronise the Persistence Context.
     * If these tickets were read as managed within the same transaction,
     * Hibernate would not see the change. The calling code here does not read tickets beforehand, so this is fine.
     *
     * @return number of updated rows
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.status = :newStatus, " +
           "       t.retryCount = 0, " +
           "       t.updatedAt = CURRENT_TIMESTAMP " +
           " WHERE t.status = :failedStatus")
    int resetFailedToNew(@Param("newStatus") TicketStatus newStatus,
                         @Param("failedStatus") TicketStatus failedStatus);

    /**
     * Bulk transition from one status to another, used at startup to recover
     * tickets that were stuck mid-processing when the previous JVM was killed.
     *
     * @param fromStatus current status (typically {@link TicketStatus#PROCESSING})
     * @param toStatus   target status (typically {@link TicketStatus#RETRY_LATER})
     * @return number of rows updated
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.status = :toStatus, " +
           "       t.updatedAt = CURRENT_TIMESTAMP " +
           " WHERE t.status = :fromStatus")
    int bulkTransitionStatus(@Param("fromStatus") TicketStatus fromStatus,
                             @Param("toStatus") TicketStatus toStatus);

    /**
     * Bulk transition for tickets STUCK in the given statuses: only rows whose
     * updatedAt is older than the cutoff are touched, so tickets legitimately
     * in-flight (recently updated) are left alone.
     *
     * Used by {@link com.smartfreight.inbox.scheduler.StuckTicketSweeper} to unstick
     * tickets whose pipeline message was lost (DLQ'd poison payload, consumer crash
     * without a JVM restart) — the time filter is what makes this safe to run
     * continuously, unlike the unconditional startup variant above.
     *
     * @param fromStatuses statuses considered potentially stuck (NEW, PROCESSING)
     * @param toStatus     target status (RETRY_LATER — picked up by RetryScheduler)
     * @param cutoff       only rows last updated BEFORE this moment are transitioned
     * @return number of rows updated
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.status = :toStatus, " +
           "       t.updatedAt = CURRENT_TIMESTAMP " +
           " WHERE t.status IN :fromStatuses " +
           "   AND t.updatedAt < :cutoff")
    int bulkTransitionStaleStatus(@Param("fromStatuses") Collection<TicketStatus> fromStatuses,
                                  @Param("toStatus") TicketStatus toStatus,
                                  @Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * Ticket counts grouped by status for tickets created on or after the cutoff (Phase 2.1).
     * One GROUP BY query feeds every per-status KPI on the analytics dashboard — statuses
     * with no tickets in the period are simply absent from the result.
     *
     * @param since start of the reporting period (inclusive)
     * @return one row per status present in the period
     */
    @Query("SELECT new com.smartfreight.inbox.repository.StatusCount(t.status, COUNT(t)) " +
           "  FROM EmailTicket t " +
           " WHERE t.createdAt >= :since " +
           " GROUP BY t.status")
    List<StatusCount> countByStatusSince(@Param("since") java.time.LocalDateTime since);

    /**
     * Ticket counts grouped by business type for tickets created on or after the cutoff
     * (Phase 2.1 per-type breakdown). IRRELEVANT tickets are excluded — junk never carries
     * a type, and including it would inflate the "unclassified" bucket; the breakdown
     * answers "what kinds of work arrived", so its denominator is the relevant tickets.
     * A null type groups tickets that never received a classification verdict.
     *
     * @param since    start of the reporting period (inclusive)
     * @param excluded status outside the breakdown (IRRELEVANT)
     * @return one row per type present in the period (null type possible)
     */
    @Query("SELECT new com.smartfreight.inbox.repository.TypeCount(t.ticketType, COUNT(t)) " +
           "  FROM EmailTicket t " +
           " WHERE t.createdAt >= :since " +
           "   AND t.status <> :excluded " +
           " GROUP BY t.ticketType")
    List<TypeCount> countByTypeSince(@Param("since") java.time.LocalDateTime since,
                                     @Param("excluded") TicketStatus excluded);

    /**
     * Per-day intake counts for the analytics volume chart (Phase 2.1).
     *
     * Native SQL: JPQL has no FILTER clause and no date truncation. Because the query is
     * native, Hibernate's {@code @SQLRestriction} on the entity does NOT apply — the
     * {@code deleted = false} predicate is repeated explicitly to stay consistent with
     * the JPQL aggregations on this page.
     *
     * @param since start of the reporting period (inclusive)
     * @return one row per day that received at least one email, ascending
     */
    @Query(nativeQuery = true, value =
           "SELECT CAST(t.created_at AS date) AS day, " +
           "       COUNT(*) AS total, " +
           "       COUNT(*) FILTER (WHERE t.status = 'IRRELEVANT') AS junk " +
           "  FROM email_ticket t " +
           " WHERE t.created_at >= :since " +
           "   AND t.deleted = false " +
           " GROUP BY CAST(t.created_at AS date) " +
           " ORDER BY day")
    List<DailyVolume> dailyVolumeSince(@Param("since") java.time.LocalDateTime since);

    /**
     * AI-confidence histogram over drafts created in the period (Phase 2.1): how the
     * deterministic heuristic (Phase 1.6) distributes — the operator reads this against
     * the auto-send threshold to judge how much would go out automatically.
     *
     * Counts every ticket with a stored confidence (it is written on each draft regardless
     * of the auto-send flag, precisely to collect this statistic). Bucket i covers
     * [i/10, (i+1)/10); LEAST folds the exact 1.0 into bucket 9. Native SQL, so the
     * soft-delete predicate is explicit (see {@link #dailyVolumeSince}).
     *
     * @param since start of the reporting period (inclusive)
     * @return one row per non-empty bucket, ascending by bucket index
     */
    @Query(nativeQuery = true, value =
           "SELECT CAST(LEAST(FLOOR(t.ai_confidence * 10), 9) AS int) AS bucket, " +
           "       COUNT(*) AS count " +
           "  FROM email_ticket t " +
           " WHERE t.ai_confidence IS NOT NULL " +
           "   AND t.created_at >= :since " +
           "   AND t.deleted = false " +
           " GROUP BY bucket " +
           " ORDER BY bucket")
    List<ConfidenceBucket> confidenceHistogramSince(@Param("since") java.time.LocalDateTime since);

    /**
     * How many tickets created on or after the cutoff breached their SLA deadline (Phase 2.1).
     * The breach flag is never reset (see {@link #markSlaBreached}), so this is a stable
     * historical count, not a snapshot of currently-overdue tickets.
     *
     * @param since start of the reporting period (inclusive)
     * @return number of breached tickets in the period
     */
    long countByCreatedAtGreaterThanEqualAndSlaBreachedTrue(java.time.LocalDateTime since);

    /**
     * Flags open tickets whose SLA deadline has passed (Phase 1.5). Used by
     * {@link com.smartfreight.inbox.scheduler.SlaBreachScheduler}.
     *
     * Deliberately does NOT touch updatedAt: the breach flag is reporting metadata,
     * not pipeline activity — bumping updatedAt here would reset the staleness clock
     * the StuckTicketSweeper relies on and keep genuinely stuck tickets unswept.
     *
     * @param now              breach cutoff (deadlines strictly before this are overdue)
     * @param exemptStatuses   statuses outside the SLA clock (CLOSED, IRRELEVANT)
     * @return number of tickets newly flagged
     */
    @Modifying
    @Query("UPDATE EmailTicket t " +
           "   SET t.slaBreached = true " +
           " WHERE t.slaBreached = false " +
           "   AND t.slaDeadlineAt IS NOT NULL " +
           "   AND t.slaDeadlineAt < :now " +
           "   AND t.status NOT IN :exemptStatuses")
    int markSlaBreached(@Param("now") java.time.LocalDateTime now,
                        @Param("exemptStatuses") Collection<TicketStatus> exemptStatuses);

    /**
     * Whether the customer has sent any inbound mail since their quote went out (Phase 2.5).
     *
     * Sender-based reply detection for {@link com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler}:
     * a fresh inbound ticket from the same address after {@code since} means the customer engaged,
     * so the pending follow-up is satisfied and no nudge is sent. Proactive tickets (our own
     * outbound nudges/updates) are excluded; the original quoted ticket is excluded by
     * {@code createdAt > since}. {@code @TenantId} scopes the query to the current tenant and
     * {@code @SQLRestriction} adds {@code deleted = false}.
     *
     * @param email cleaned, lower-cased customer address (matched within the raw senderEmail)
     * @param since the moment the quote was sent (strictly after)
     * @return true if any qualifying inbound ticket exists
     */
    @Query("SELECT COUNT(t) > 0 FROM EmailTicket t " +
           " WHERE t.proactive = false " +
           "   AND t.createdAt > :since " +
           "   AND LOWER(t.senderEmail) LIKE CONCAT('%', :email, '%')")
    boolean existsInboundReplyFrom(@Param("email") String email,
                                   @Param("since") java.time.LocalDateTime since);
}
