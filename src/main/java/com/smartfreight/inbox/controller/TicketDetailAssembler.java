package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.KeycloakAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * What every {@code /api/tickets/{id}} endpoint shares: loading the ticket (404 when it is missing
 * or belongs to another tenant — the repository is tenant-scoped) and building the detail DTO the
 * endpoints answer with.
 */
@Component
class TicketDetailAssembler {

    private static final Logger log = LoggerFactory.getLogger(TicketDetailAssembler.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TenantDocumentRepository documentRepository;
    private final KeycloakAdminService keycloakAdminService;

    TicketDetailAssembler(EmailTicketRepository emailTicketRepository,
                          TenantDocumentRepository documentRepository,
                          KeycloakAdminService keycloakAdminService) {
        this.emailTicketRepository = emailTicketRepository;
        this.documentRepository = documentRepository;
        this.keycloakAdminService = keycloakAdminService;
    }

    EmailTicket requireTicket(Long id) {
        return emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found: " + id));
    }

    static boolean isTerminal(EmailTicket ticket) {
        return ticket.getStatus() == TicketStatus.CLOSED || ticket.getStatus() == TicketStatus.AUTO_SENT;
    }

    /** Builds the detail DTO, resolving the attached document and available documents. */
    TicketDetailDto buildDetail(EmailTicket ticket) {
        TenantDocument attached = ticket.getAttachedDocumentId() == null
                ? null
                : documentRepository.findById(ticket.getAttachedDocumentId()).orElse(null);
        List<TenantDocument> available = (ticket.getTicketType() == TicketType.DOCUMENT_REQUEST
                && ticket.getExtractedIdentifier() != null)
                ? documentRepository.findByIdentifierOrderByUploadedAtDesc(ticket.getExtractedIdentifier())
                : List.of();
        return TicketDetailDto.from(ticket, attached, available, resolveAssigneeEmail(ticket));
    }

    /**
     * Resolves one assigned ticket's owner to a display email. Best-effort: a missing
     * assignee or an unreachable Keycloak yields null (the UUID is still on the DTO), never an error —
     * showing the ticket must not depend on the identity service being up.
     */
    private String resolveAssigneeEmail(EmailTicket ticket) {
        String uid = ticket.getAssigneeUserId();
        if (uid == null || ticket.getTenantId() == null) {
            return null;
        }
        try {
            return keycloakAdminService.userEmailsByTenant(ticket.getTenantId()).get(uid);
        } catch (Exception e) {
            log.warn("Could not resolve assignee email for ticket {}: {}", ticket.getId(), e.getMessage());
            return null;
        }
    }
}
