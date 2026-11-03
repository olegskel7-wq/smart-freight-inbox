package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
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
import java.util.Map;

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

    /** Builds the detail DTO, resolving the attached document and available documents. */
    TicketDetailDto buildDetail(EmailTicket ticket) {
        TenantDocument attached = ticket.getAttachedDocumentId() == null
                ? null
                : documentRepository.findById(ticket.getAttachedDocumentId()).orElse(null);
        List<TenantDocument> available = (ticket.getTicketType() == TicketType.DOCUMENT_REQUEST
                && ticket.getExtractedIdentifier() != null)
                ? documentRepository.findByIdentifierOrderByUploadedAtDesc(ticket.getExtractedIdentifier())
                : List.of();
        String uid = ticket.getAssigneeUserId();
        String assigneeEmail = uid == null ? null : assigneeEmails(List.of(ticket), ticket.getTenantId()).get(uid);
        return TicketDetailDto.from(ticket, attached, available, assigneeEmail);
    }

    /**
     * Resolves the assignees of these tickets to a {@code uuid → email} map in a single Keycloak
     * call — and only when one of them is assigned, so the common (all-unclaimed) path stays
     * call-free. Best-effort: no tenant or an unreachable Keycloak yields an empty map (the UUID is
     * still on the DTO), never an error — showing tickets must not depend on the identity service.
     */
    Map<String, String> assigneeEmails(List<EmailTicket> tickets, Long tenantId) {
        boolean anyAssigned = tickets.stream().anyMatch(t -> t.getAssigneeUserId() != null);
        if (!anyAssigned || tenantId == null) {
            return Map.of();
        }
        try {
            return keycloakAdminService.userEmailsByTenant(tenantId);
        } catch (Exception e) {
            log.warn("Could not resolve assignee emails for tenant {}: {}", tenantId, e.getMessage());
            return Map.of();
        }
    }
}
