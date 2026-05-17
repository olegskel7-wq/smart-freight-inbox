package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
     * Finds all tickets with the specified status.
     *
     * @param status the status to search for
     * @return list of tickets
     */
    List<EmailTicket> findByStatus(TicketStatus status);

    /**
     * Finds all tickets with the specified status and sort order.
     * Used in the UI list to filter and sort simultaneously.
     *
     * @param status   the status to search for
     * @param sort     sort specification
     * @return sorted list of tickets
     */
    List<EmailTicket> findByStatus(TicketStatus status, Sort sort);

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
     * @param status     the status to search for (typically RETRY_LATER)
     * @param maxRetries maximum retryCount (exclusive upper bound)
     * @return list of tickets eligible for retry
     */
    List<EmailTicket> findByStatusAndRetryCountLessThan(TicketStatus status, int maxRetries);

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
}
