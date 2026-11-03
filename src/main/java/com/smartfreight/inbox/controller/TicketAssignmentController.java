package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Who owns a ticket in the shared queue: an operator claims or releases it, an admin hands it
 * to someone else.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketAssignmentController {

    private final EmailTicketRepository emailTicketRepository;
    private final TicketAuditService auditService;
    private final TicketDetailAssembler details;

    public TicketAssignmentController(EmailTicketRepository emailTicketRepository,
                                      TicketAuditService auditService,
                                      TicketDetailAssembler details) {
        this.emailTicketRepository = emailTicketRepository;
        this.auditService = auditService;
        this.details = details;
    }

    /** Request body for {@code POST /api/tickets/{id}/assign}: null/blank userId self-claims. */
    public record AssignRequest(String userId) {}

    /**
     * POST /api/tickets/{id}/assign — claims the ticket. A blank/absent {@code userId}
     * self-claims (any operator); assigning to a different operator requires ADMIN (403 otherwise).
     * The {@code @PostUpdate} live event refreshes every operator's list. Independent of status —
     * ownership is orthogonal to the ticket lifecycle.
     */
    @Transactional
    @PostMapping("/{id}/assign")
    public TicketDetailDto assign(@PathVariable Long id,
                                  @RequestBody(required = false) AssignRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        EmailTicket ticket = details.requireTicket(id);
        String me = jwt == null ? null : jwt.getSubject();
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated operator.");
        }
        String target = (request == null || request.userId() == null || request.userId().isBlank())
                ? me : request.userId().trim();
        if (!target.equals(me) && !isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an admin can assign tickets to other operators.");
        }
        ticket.setAssigneeUserId(target);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(id, TicketAuditService.ACTION_ASSIGN, "to=" + target);
        return details.buildDetail(ticket);
    }

    /** POST /api/tickets/{id}/unassign — releases the ticket back to the unclaimed queue. */
    @Transactional
    @PostMapping("/{id}/unassign")
    public TicketDetailDto unassign(@PathVariable Long id) {
        EmailTicket ticket = details.requireTicket(id);
        ticket.setAssigneeUserId(null);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(id, TicketAuditService.ACTION_UNASSIGN, null);
        return details.buildDetail(ticket);
    }

    /** True if the bearer carries the ADMIN or SUPER_ADMIN realm role (assign-to-other gate). */
    private boolean isAdmin(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        Object raw = jwt.getClaim("roles");
        if (raw instanceof Collection<?> roles) {
            for (Object r : roles) {
                String role = String.valueOf(r);
                if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }
}
