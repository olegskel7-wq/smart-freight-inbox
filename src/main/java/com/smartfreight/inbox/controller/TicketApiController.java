package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.PageDto;
import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.controller.dto.TicketStatsDto;
import com.smartfreight.inbox.controller.dto.TicketSummaryDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.tenancy.TenantClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON API backing the React {@code /tickets} pages. It speaks DTOs over
 * {@code /api/tickets} and is secured by the stateless resource-server chain (bearer JWT).
 * This controller serves the list, the summary strip and the ticket itself (detail, close, delete);
 * the reply lives in {@link TicketReplyController}.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketApiController {

    private static final Logger log = LoggerFactory.getLogger(TicketApiController.class);

    /** Same sort whitelist as the Thymeleaf controller — guards against PropertyReferenceException. */
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "id", "senderEmail", "subject", "extractedIdentifier",
            "status", "ticketType", "slaDeadlineAt", "aiConfidence", "createdAt", "updatedAt"
    );
    private static final String DEFAULT_SORT = "createdAt";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Escalated tickets (negative tone or HIGH urgency) float to the top of the default
     * view. Composed AHEAD of the createdAt sort so the most pressing work is seen first; applied only
     * when the operator hasn't picked an explicit sort column (then their choice wins). urgency is an
     * ORDINAL column (HIGH=2) so {@code DESC} ranks it correctly as a plain column.
     */
    private static final Sort ESCALATION_SORT = Sort.by(
            Sort.Order.desc("sentimentNegative"),
            Sort.Order.desc("urgency"));

    /** Actionable backlog for the summary strip — every status outside the terminal three. */
    private static final Set<TicketStatus> OPEN_STATUSES = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.NEW, TicketStatus.PROCESSING,
            TicketStatus.DRAFT_READY, TicketStatus.RETRY_LATER, TicketStatus.FAILED);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketAuditService auditService;
    private final KeycloakAdminService keycloakAdminService;
    private final TicketDetailAssembler details;

    public TicketApiController(EmailTicketRepository emailTicketRepository,
                               TicketAuditService auditService,
                               KeycloakAdminService keycloakAdminService,
                               TicketDetailAssembler details) {
        this.emailTicketRepository = emailTicketRepository;
        this.auditService = auditService;
        this.keycloakAdminService = keycloakAdminService;
        this.details = details;
    }

    /** Request body for {@code POST /api/tickets/{id}/assign}: null/blank userId self-claims. */
    public record AssignRequest(String userId) {}

    /**
     * GET /api/tickets — paginated, sorted, optionally status-filtered list. The {@code assignee}
     * filter scopes the shared queue: {@code mine} = the caller's tickets,
     * {@code unassigned} = unclaimed work, {@code all} (default) = everything.
     */
    @GetMapping
    public PageDto<TicketSummaryDto> list(
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "dir", defaultValue = "desc") String dir,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "assignee", defaultValue = "all") String assignee,
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @AuthenticationPrincipal Jwt jwt) {

        // An explicit, valid sort column means the operator picked it (the SPA omits `sort` otherwise);
        // an absent/invalid value is the default view. Only the default view floats escalated tickets up,
        // so an explicit Date sort really wins — even when it is createdAt itself.
        boolean explicitSort = sort != null && SORTABLE_COLUMNS.contains(sort);
        String safeSort = explicitSort ? sort : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Sort sortSpec = Sort.by(direction, safeSort);
        if (!explicitSort) {
            sortSpec = ESCALATION_SORT.and(sortSpec);
        }
        Pageable pageable = PageRequest.of(safePage, safeSize, sortSpec);
        TicketStatus statusFilter = parseStatusOrNull(status);
        String mode = assignee == null ? "all" : assignee.trim().toLowerCase();

        // A summary-chip drill-down: each `view` reuses the exact set the chip counted,
        // so the count and the list it links to can never diverge. It takes precedence over the
        // status/assignee filters (sort still applies via the pageable).
        Page<EmailTicket> viewPage = resolveView(view, pageable);
        if (viewPage != null) {
            Map<String, String> viewEmails = resolveAssigneeEmails(viewPage.getContent(), jwt);
            return PageDto.of(viewPage, t -> TicketSummaryDto.from(t,
                    t.getAssigneeUserId() == null ? null : viewEmails.get(t.getAssigneeUserId())));
        }

        Page<EmailTicket> ticketPage;
        if ("mine".equals(mode)) {
            String me = jwt == null ? null : jwt.getSubject();
            ticketPage = (me == null)
                    ? Page.empty(pageable)
                    : emailTicketRepository.findByAssignee(me, statusFilter, pageable);
        } else if ("unassigned".equals(mode)) {
            ticketPage = emailTicketRepository.findUnassigned(statusFilter, pageable);
        } else if (statusFilter != null) {
            ticketPage = emailTicketRepository.findByStatus(statusFilter, pageable);
        } else {
            ticketPage = emailTicketRepository.findAll(pageable);
        }

        Map<String, String> emails = resolveAssigneeEmails(ticketPage.getContent(), jwt);
        return PageDto.of(ticketPage, t -> TicketSummaryDto.from(t,
                t.getAssigneeUserId() == null ? null : emails.get(t.getAssigneeUserId())));
    }

    /**
     * GET /api/tickets/summary — at-a-glance counts for the dashboard strip. Computed
     * on request and tenant-scoped (every count is a {@code @TenantId} derived query). The literal
     * {@code /summary} path is matched ahead of {@code /{id}}, so it never collides with detail.
     */
    @GetMapping("/summary")
    public TicketStatsDto summary() {
        return new TicketStatsDto(
                emailTicketRepository.countByStatusIn(OPEN_STATUSES),
                emailTicketRepository.countByStatus(TicketStatus.DRAFT_READY),
                emailTicketRepository.countByStatusInAndSlaBreachedTrue(OPEN_STATUSES),
                emailTicketRepository.countByStatusAndUpdatedAtGreaterThanEqual(
                        TicketStatus.AUTO_SENT, LocalDate.now().atStartOfDay()));
    }

    /** GET /api/tickets/{id} — full ticket detail (404 if missing). */
    @GetMapping("/{id}")
    public TicketDetailDto detail(@PathVariable Long id) {
        return details.buildDetail(details.requireTicket(id));
    }

    /** POST /api/tickets/{id}/close — close the ticket (AUTO_SENT is terminal → no-op). */
    @Transactional
    @PostMapping("/{id}/close")
    public TicketDetailDto close(@PathVariable Long id) {
        EmailTicket ticket = details.requireTicket(id);
        if (ticket.getStatus() != TicketStatus.AUTO_SENT) {
            ticket.setStatus(TicketStatus.CLOSED);
            ticket.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(ticket);
            auditService.record(id, TicketAuditService.ACTION_CLOSE, null);
        }
        return details.buildDetail(ticket);
    }

    /** DELETE /api/tickets/{id} — soft delete (409 while PROCESSING). */
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        EmailTicket ticket = details.requireTicket(id);
        if (ticket.getStatus() == TicketStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        emailTicketRepository.delete(ticket);
        auditService.record(id, TicketAuditService.ACTION_DELETE, null);
        log.info("Ticket {} soft-deleted (API)", id);
        return ResponseEntity.noContent().build();
    }

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

    // ---- helpers ----

    /**
     * Resolves the assignees on one list page to a {@code uuid → email} map in a single Keycloak
     * call — and only when the page actually has an assigned ticket, so the common (all-unclaimed)
     * path stays call-free. Best-effort: an unreachable Keycloak yields an empty map.
     */
    private Map<String, String> resolveAssigneeEmails(List<EmailTicket> tickets, Jwt jwt) {
        boolean anyAssigned = tickets.stream().anyMatch(t -> t.getAssigneeUserId() != null);
        if (!anyAssigned) {
            return Map.of();
        }
        Long tenantId = TenantClaims.fromJwt(jwt);
        if (tenantId == null) {
            return Map.of();
        }
        try {
            return keycloakAdminService.userEmailsByTenant(tenantId);
        } catch (Exception e) {
            log.warn("Could not resolve assignee emails: {}", e.getMessage());
            return Map.of();
        }
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

    /**
     * Resolves a summary-chip drill-down to a page, or null when no recognised view is
     * requested (the caller then falls back to the normal status/assignee filtering). Each case
     * reuses the same definition as its counterpart count in {@link #summary()}.
     */
    private Page<EmailTicket> resolveView(String view, Pageable pageable) {
        if (view == null || view.isBlank()) {
            return null;
        }
        return switch (view.trim().toLowerCase()) {
            case "open" -> emailTicketRepository.findByStatusIn(OPEN_STATUSES, pageable);
            case "awaiting" -> emailTicketRepository.findByStatus(TicketStatus.DRAFT_READY, pageable);
            case "breached" -> emailTicketRepository.findByStatusInAndSlaBreachedTrue(OPEN_STATUSES, pageable);
            case "autosent_today" -> emailTicketRepository.findByStatusAndUpdatedAtGreaterThanEqual(
                    TicketStatus.AUTO_SENT, LocalDate.now().atStartOfDay(), pageable);
            default -> null;
        };
    }

    private TicketStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
