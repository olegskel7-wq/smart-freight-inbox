package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TicketAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
