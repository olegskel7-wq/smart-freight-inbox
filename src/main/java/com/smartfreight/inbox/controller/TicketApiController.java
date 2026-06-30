package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.PageDto;
import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.controller.dto.TicketStatsDto;
import com.smartfreight.inbox.controller.dto.TicketSummaryDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.KeycloakAdminService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.tenancy.TenantClaims;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import com.smartfreight.inbox.webhook.WebhookPayloads;
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
 * JSON API backing the React {@code /tickets} pages (Phase 3.2). It speaks DTOs over
 * {@code /api/tickets} and is secured by the stateless resource-server chain (bearer JWT).
 * Since the Phase 3.2 cutover this is the sole ticket controller — the Thymeleaf
 * {@code TicketViewController} it was migrated from has been removed, and its draft/send
 * orchestration now lives here.
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
     * Phase 3.10: escalated tickets (negative tone or HIGH urgency) float to the top of the default
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
    private final EmailReplyService emailReplyService;
    private final TicketAuditService auditService;
    private final SendRateLimiter sendRateLimiter;
    private final QuoteFollowUpService quoteFollowUpService;
    private final TenantDocumentRepository documentRepository;
    private final FeedbackLoopService feedbackLoopService;
    private final KeycloakAdminService keycloakAdminService;
    private final WebhookEventPublisher webhookEventPublisher;

    public TicketApiController(EmailTicketRepository emailTicketRepository,
                               EmailReplyService emailReplyService,
                               TicketAuditService auditService,
                               SendRateLimiter sendRateLimiter,
                               QuoteFollowUpService quoteFollowUpService,
                               TenantDocumentRepository documentRepository,
                               FeedbackLoopService feedbackLoopService,
                               KeycloakAdminService keycloakAdminService,
                               WebhookEventPublisher webhookEventPublisher) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.auditService = auditService;
        this.sendRateLimiter = sendRateLimiter;
        this.quoteFollowUpService = quoteFollowUpService;
        this.documentRepository = documentRepository;
        this.feedbackLoopService = feedbackLoopService;
        this.keycloakAdminService = keycloakAdminService;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    /** Request body for {@code POST /api/tickets/{id}/draft}: action = save | close | send. */
    public record DraftRequest(String action, String draftReply) {}

    /** Request body for {@code POST /api/tickets/{id}/attach-document}: null id detaches. */
    public record AttachRequest(Long documentId) {}

    /** Request body for {@code POST /api/tickets/{id}/assign}: null/blank userId self-claims (Phase 3.3 S2). */
    public record AssignRequest(String userId) {}

    /**
     * GET /api/tickets — paginated, sorted, optionally status-filtered list. The {@code assignee}
     * filter (Phase 3.3 S2) scopes the shared queue: {@code mine} = the caller's tickets,
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
        // so an explicit Date sort really wins — even when it is createdAt itself (Phase 3.10).
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

        // A summary-chip drill-down (Phase 3 UI): each `view` reuses the exact set the chip counted,
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
     * GET /api/tickets/summary — at-a-glance counts for the dashboard strip (Phase 3 UI). Computed
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
        return buildDetail(requireTicket(id));
    }

    /**
     * POST /api/tickets/{id}/draft — save / close / send the draft. Returns the updated detail;
     * 429 when send is rate-limited, 502 when SMTP fails. CLOSED/AUTO_SENT are terminal →
     * unchanged detail.
     */
    @Transactional
    @PostMapping("/{id}/draft")
    public ResponseEntity<TicketDetailDto> updateDraft(@PathVariable Long id,
                                                       @RequestBody DraftRequest request) {
        EmailTicket ticket = requireTicket(id);
        if (isTerminal(ticket)) {
            return ResponseEntity.ok(buildDetail(ticket));
        }

        String action = request.action() == null ? "save" : request.action();
        ticket.setDraftReply(request.draftReply());

        if ("send".equalsIgnoreCase(action)) {
            if (!sendRateLimiter.tryAcquire(id)) {
                log.warn("Send rate-limited for ticket {} — within {}s cooldown",
                        id, sendRateLimiter.getCooldown().toSeconds());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            }
            try {
                emailReplyService.sendReply(ticket);
                ticket.setStatus(TicketStatus.CLOSED);
                log.info("Ticket {} draft sent to {} and closed (API)", id, ticket.getSenderEmail());
                auditService.record(id, TicketAuditService.ACTION_SEND, "to=" + ticket.getSenderEmail());
                quoteFollowUpService.armFromTicket(ticket);
                feedbackLoopService.captureIfEdited(ticket);
                emitReplyWebhooks(ticket);
            } catch (Exception e) {
                log.error("Failed to send reply for ticket {} (API): {}", id, e.getMessage(), e);
                auditService.record(id, TicketAuditService.ACTION_SEND, "FAILED: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
        } else if ("close".equalsIgnoreCase(action)) {
            ticket.setStatus(TicketStatus.CLOSED);
            auditService.record(id, TicketAuditService.ACTION_CLOSE, null);
        } else if (ticket.getStatus() != TicketStatus.DRAFT_READY) {
            ticket.setStatus(TicketStatus.DRAFT_READY);
            auditService.record(id, TicketAuditService.ACTION_DRAFT_EDIT, null);
        } else {
            auditService.record(id, TicketAuditService.ACTION_DRAFT_EDIT, null);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        return ResponseEntity.ok(buildDetail(ticket));
    }

    /**
     * Phase 3.5 S2: queues outbound webhook deliveries after a reply is sent — {@code reply.sent} for
     * every reply, plus {@code quote.sent} when the ticket is an RFQ (a price quote went out). Runs inside
     * the send transaction so the deliveries are atomic with the close; best-effort inside the publisher.
     * Mirrors the {@code armFromTicket} RFQ guard — a sent RFQ reply is both a reply and a quote.
     */
    private void emitReplyWebhooks(EmailTicket ticket) {
        Map<String, Object> data = WebhookPayloads.ticketData(ticket);
        webhookEventPublisher.publish(WebhookEventType.REPLY_SENT, ticket.getTenantId(), data);
        if (ticket.getTicketType() == TicketType.RFQ) {
            webhookEventPublisher.publish(WebhookEventType.QUOTE_SENT, ticket.getTenantId(), data);
        }
    }

    /** POST /api/tickets/{id}/close — close the ticket (AUTO_SENT is terminal → no-op). */
    @Transactional
    @PostMapping("/{id}/close")
    public TicketDetailDto close(@PathVariable Long id) {
        EmailTicket ticket = requireTicket(id);
        if (ticket.getStatus() != TicketStatus.AUTO_SENT) {
            ticket.setStatus(TicketStatus.CLOSED);
            ticket.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(ticket);
            auditService.record(id, TicketAuditService.ACTION_CLOSE, null);
        }
        return buildDetail(ticket);
    }

    /** DELETE /api/tickets/{id} — soft delete (409 while PROCESSING). */
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        EmailTicket ticket = requireTicket(id);
        if (ticket.getStatus() == TicketStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        emailTicketRepository.delete(ticket);
        auditService.record(id, TicketAuditService.ACTION_DELETE, null);
        log.info("Ticket {} soft-deleted (API)", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/tickets/{id}/attach-document — attach an on-file document to the reply, or detach
     * when {@code documentId} is null (Phase 2.7). Terminal tickets → unchanged detail.
     */
    @Transactional
    @PostMapping("/{id}/attach-document")
    public TicketDetailDto attachDocument(@PathVariable Long id, @RequestBody AttachRequest request) {
        EmailTicket ticket = requireTicket(id);
        if (isTerminal(ticket)) {
            return buildDetail(ticket);
        }
        if (request.documentId() == null) {
            ticket.setAttachedDocumentId(null);
        } else {
            TenantDocument doc = documentRepository.findById(request.documentId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Document not found: " + request.documentId()));
            ticket.setAttachedDocumentId(doc.getId());
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        return buildDetail(ticket);
    }

    /**
     * POST /api/tickets/{id}/assign — claims the ticket (Phase 3.3 S2). A blank/absent {@code userId}
     * self-claims (any operator); assigning to a different operator requires ADMIN (403 otherwise).
     * The {@code @PostUpdate} live event refreshes every operator's list. Independent of status —
     * ownership is orthogonal to the ticket lifecycle.
     */
    @Transactional
    @PostMapping("/{id}/assign")
    public TicketDetailDto assign(@PathVariable Long id,
                                  @RequestBody(required = false) AssignRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        EmailTicket ticket = requireTicket(id);
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
        return buildDetail(ticket);
    }

    /** POST /api/tickets/{id}/unassign — releases the ticket back to the unclaimed queue (Phase 3.3 S2). */
    @Transactional
    @PostMapping("/{id}/unassign")
    public TicketDetailDto unassign(@PathVariable Long id) {
        EmailTicket ticket = requireTicket(id);
        ticket.setAssigneeUserId(null);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        auditService.record(id, TicketAuditService.ACTION_UNASSIGN, null);
        return buildDetail(ticket);
    }

    // ---- helpers ----

    private EmailTicket requireTicket(Long id) {
        return emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found: " + id));
    }

    private boolean isTerminal(EmailTicket ticket) {
        return ticket.getStatus() == TicketStatus.CLOSED || ticket.getStatus() == TicketStatus.AUTO_SENT;
    }

    /** Builds the detail DTO, resolving the attached document and available documents (Phase 2.7). */
    private TicketDetailDto buildDetail(EmailTicket ticket) {
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
     * Resolves one assigned ticket's owner to a display email (Phase 3.3 S2). Best-effort: a missing
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

    /** True if the bearer carries the ADMIN or SUPER_ADMIN realm role (assign-to-other gate, Phase 3.3 S2). */
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
     * Resolves a summary-chip drill-down (Phase 3 UI) to a page, or null when no recognised view is
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
