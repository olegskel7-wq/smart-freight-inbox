package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TicketAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
