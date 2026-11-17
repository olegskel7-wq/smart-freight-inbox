package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Operator actions on a ticket that are not the reply: close, delete and the shared-queue
 * ownership (claim / hand-over / release). Each method takes a ticket the caller already loaded,
 * records its audit row and saves in one transaction (it joins the endpoint's). Who may do what
 * (the admin check on hand-over) is decided by the caller.
 */
@Service
public class TicketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(TicketWorkflowService.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketAuditService auditService;
    private final SlaService slaService;

    public TicketWorkflowService(EmailTicketRepository emailTicketRepository,
                                 TicketAuditService auditService,
                                 SlaService slaService) {
        this.emailTicketRepository = emailTicketRepository;
        this.auditService = auditService;
        this.slaService = slaService;
    }

    /** Closes the ticket; AUTO_SENT is terminal, so closing it is a no-op. */
    @Transactional
    public void close(EmailTicket ticket) {
        if (ticket.getStatus() == TicketStatus.AUTO_SENT) {
            return;
        }
        slaService.markBreachedIfOverdue(ticket, LocalDateTime.now());
        ticket.setStatus(TicketStatus.CLOSED);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(ticket.getId(), TicketAuditService.ACTION_CLOSE, null);
    }

    /**
     * Soft-deletes the ticket, unless the pipeline is working on it right now.
     *
     * @return false (nothing deleted) while the ticket is PROCESSING
     */
    @Transactional
    public boolean delete(EmailTicket ticket) {
        if (ticket.getStatus() == TicketStatus.PROCESSING) {
            return false;
        }
        emailTicketRepository.delete(ticket);
        auditService.record(ticket.getId(), TicketAuditService.ACTION_DELETE, null);
        log.info("Ticket {} soft-deleted (API)", ticket.getId());
        return true;
    }

    /**
     * Gives the ticket to {@code userId} (a Keycloak user UUID). The {@code @PostUpdate} live event
     * refreshes every operator's list. Independent of status — ownership is orthogonal to the lifecycle.
     */
    @Transactional
    public void assign(EmailTicket ticket, String userId) {
        ticket.setAssigneeUserId(userId);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(ticket.getId(), TicketAuditService.ACTION_ASSIGN, "to=" + userId);
    }

    /** Releases the ticket back to the unclaimed queue. */
    @Transactional
    public void unassign(EmailTicket ticket) {
        ticket.setAssigneeUserId(null);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(ticket.getId(), TicketAuditService.ACTION_UNASSIGN, null);
    }
}
