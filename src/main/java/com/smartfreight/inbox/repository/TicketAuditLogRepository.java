package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TicketAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for {@link TicketAuditLog} entries. Append-only — only {@code save} is expected
 * from application code; reads exist for an eventual audit-viewer page.
 */
@Repository
public interface TicketAuditLogRepository extends JpaRepository<TicketAuditLog, Long> {

    /**
     * Returns all audit entries for a ticket, newest first.
     *
     * @param ticketId ticket identifier
     * @return audit entries in reverse chronological order
     */
    List<TicketAuditLog> findByTicketIdOrderByCreatedAtDesc(Long ticketId);

    /**
     * Counts actions of one kind recorded on or after the cutoff (Phase 2.1 analytics).
     * The audit log is the source of truth for "how many replies went out" — counting
     * tickets by status would miss manual sends (a CLOSED ticket may or may not have
     * been replied to) and attribute sends to the ticket's creation date instead of
     * the moment the operator actually pressed Send.
     *
     * @param action action code, e.g. {@code SEND} or {@code AUTO_SEND}
     * @param since  start of the reporting period (inclusive)
     * @return number of matching audit entries
     */
    long countByActionAndCreatedAtGreaterThanEqual(String action, LocalDateTime since);

    /**
     * Average first-response time in seconds for tickets first answered in the period
     * (Phase 2.1). "First answered" = the earliest SEND or AUTO_SEND audit entry of a
     * ticket; the clock starts at the ticket's createdAt (email received). Attribution
     * follows the reply KPIs: a ticket belongs to the period its first reply went out in,
     * regardless of when the email arrived — and repeat replies never re-count a ticket.
     *
     * Native SQL (interval arithmetic + a grouped subquery are beyond JPQL). The join
     * deliberately skips soft-deleted tickets to stay consistent with the rest of the
     * dashboard ({@code @SQLRestriction} does not apply to native queries).
     *
     * @param since start of the reporting period (inclusive)
     * @return mean seconds from receipt to first reply, or null when nothing was answered
     */
    @Query(nativeQuery = true, value =
           "SELECT AVG(EXTRACT(EPOCH FROM (f.first_send - t.created_at))) " +
           "  FROM (SELECT a.ticket_id, MIN(a.created_at) AS first_send " +
           "          FROM ticket_audit_log a " +
           "         WHERE a.action IN ('SEND', 'AUTO_SEND') " +
           "         GROUP BY a.ticket_id) f " +
           "  JOIN email_ticket t ON t.id = f.ticket_id AND t.deleted = false " +
           " WHERE f.first_send >= :since")
    Double avgFirstResponseSeconds(@Param("since") LocalDateTime since);
}
