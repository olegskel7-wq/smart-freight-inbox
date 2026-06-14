package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Thymeleaf UI controller for the operator browser interface.
 *
 * Authentication via Keycloak OIDC (Phase 2.3); the session cookie still backs the login.
 * The only external controller — the REST API was removed as unnecessary for the demo version.
 */
@Controller
public class TicketViewController {

    private static final Logger log = LoggerFactory.getLogger(TicketViewController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;
    private final TicketAuditService auditService;
    private final SendRateLimiter sendRateLimiter;
    private final QuoteFollowUpService quoteFollowUpService;
    private final TenantDocumentRepository documentRepository;

    public TicketViewController(EmailTicketRepository emailTicketRepository,
                                EmailReplyService emailReplyService,
                                TicketAuditService auditService,
                                SendRateLimiter sendRateLimiter,
                                QuoteFollowUpService quoteFollowUpService,
                                TenantDocumentRepository documentRepository) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.auditService = auditService;
        this.sendRateLimiter = sendRateLimiter;
        this.quoteFollowUpService = quoteFollowUpService;
        this.documentRepository = documentRepository;
    }

    /**
     * GET / — go to tickets. Unauthenticated requests never reach here: Spring Security
     * intercepts them and redirects to Keycloak (Phase 2.3, single oauth2 client).
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/tickets";
    }

    /**
     * Whitelist of allowed sort columns.
     *
     * Any other sort value is ignored and replaced with createdAt —
     * protection against PropertyReferenceException if the client passes
     * a non-existent JPA field name.
     */
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "id", "senderEmail", "subject", "extractedIdentifier",
            "status", "ticketType", "slaDeadlineAt", "aiConfidence", "createdAt", "updatedAt"
    );
    private static final String DEFAULT_SORT = "createdAt";

    private static final int PAGE_SIZE = 50;

    /**
     * GET /tickets — paginated ticket list with server-side sorting and status filter.
     *
     * Parameters:
     * — {@code ?sort=<column>} — sort column (whitelist above)
     * — {@code ?dir=asc|desc} — direction (default: desc — newest first)
     * — {@code ?status=DRAFT_READY} — optional status filter
     * — {@code ?page=0} — zero-based page number (default: 0)
     *
     * @param sort   sort column name
     * @param dir    sort direction
     * @param status optional status filter
     * @param page   zero-based page number
     * @param model  Thymeleaf model object
     * @return template name "tickets"
     */
    @GetMapping("/tickets")
    public String listTickets(@RequestParam(name = "sort", defaultValue = DEFAULT_SORT) String sort,
                              @RequestParam(name = "dir", defaultValue = "desc") String dir,
                              @RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "page", defaultValue = "0") int page,
                              Model model) {

        String safeSort = SORTABLE_COLUMNS.contains(sort) ? sort : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);

        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by(direction, safeSort));

        TicketStatus statusFilter = parseStatusOrNull(status);
        Page<EmailTicket> ticketPage = (statusFilter != null)
                ? emailTicketRepository.findByStatus(statusFilter, pageable)
                : emailTicketRepository.findAll(pageable);

        model.addAttribute("ticketPage", ticketPage);
        model.addAttribute("tickets", ticketPage.getContent());
        model.addAttribute("sort", safeSort);
        model.addAttribute("dir", direction.name().toLowerCase());
        model.addAttribute("statusFilter", statusFilter == null ? "" : statusFilter.name());
        model.addAttribute("allStatuses", TicketStatus.values());
        return "tickets";
    }

    /**
     * Converts a text parameter to a TicketStatus enum value, or returns null if blank/invalid.
     *
     * The UI is fault-tolerant — an invalid status value is simply ignored.
     */
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

    /**
     * GET /tickets/{id} — renders the ticket detail page.
     *
     * ResponseStatusException(404) allows Spring Boot to:
     * 1) return the correct HTTP 404 status (not a 500 "whitelabel" error);
     * 2) delegate to BasicErrorController, which will later render templates/error.html.
     * GlobalExceptionHandler does NOT apply here — it is scoped to @RestController classes.
     *
     * @param id    ticket identifier
     * @param model Thymeleaf model object
     * @return template name "ticket-detail" or 404
     */
    @GetMapping("/tickets/{id}")
    public String ticketDetail(@PathVariable Long id, Model model) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
        model.addAttribute("ticket", ticket);

        // Phase 2.7: for document requests, surface the currently attached document and offer the
        // documents on file for this shipment so the operator can attach one before sending.
        if (ticket.getAttachedDocumentId() != null) {
            model.addAttribute("attachedDocument",
                    documentRepository.findById(ticket.getAttachedDocumentId()).orElse(null));
        }
        if (ticket.getTicketType() == TicketType.DOCUMENT_REQUEST
                && ticket.getExtractedIdentifier() != null) {
            model.addAttribute("availableDocuments",
                    documentRepository.findByIdentifierOrderByUploadedAtDesc(ticket.getExtractedIdentifier()));
        }
        return "ticket-detail";
    }

    /**
     * POST /tickets/{id}/attach-document — operator manually attaches a stored document to a
     * DOCUMENT_REQUEST reply (Phase 2.7) when none was matched automatically. The chosen document
     * rides the next send (manual or auto) via {@link EmailReplyService}. An empty value detaches.
     *
     * @param id         ticket identifier
     * @param documentId tenant_document id, or blank to clear the attachment
     * @return redirect to the ticket detail page
     */
    @Transactional
    @PostMapping("/tickets/{id}/attach-document")
    public String attachDocument(@PathVariable Long id,
                                 @RequestParam(name = "documentId", required = false) Long documentId,
                                 RedirectAttributes redirectAttributes) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));
        if (ticket.getStatus() == TicketStatus.CLOSED || ticket.getStatus() == TicketStatus.AUTO_SENT) {
            return "redirect:/tickets/" + id; // terminal — the reply already went out
        }
        if (documentId == null) {
            ticket.setAttachedDocumentId(null);
            redirectAttributes.addFlashAttribute("successMessage", "Attachment removed.");
        } else {
            TenantDocument doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
            ticket.setAttachedDocumentId(doc.getId());
            redirectAttributes.addFlashAttribute("successMessage", "Attached " + doc.getFilename() + ".");
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        return "redirect:/tickets/" + id;
    }

    /**
     * POST /tickets/{id}/delete — soft-deletes a ticket (sets deleted = true via @SQLDelete).
     *
     * The record remains in the database for audit purposes but is hidden from all queries
     * filtered by @SQLRestriction (deleted = false).
     *
     * @param id                 ticket identifier
     * @param redirectAttributes flash message on success
     * @return redirect to the ticket list
     */
    @Transactional
    @PostMapping("/tickets/{id}/delete")
    public String deleteTicket(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        EmailTicket ticket = emailTicketRepository.findById(id).orElse(null);
        if (ticket == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Ticket #" + id + " not found.");
            return "redirect:/tickets";
        }
        if (ticket.getStatus() == TicketStatus.PROCESSING) {
            log.warn("Ticket {} cannot be deleted while in PROCESSING state", id);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Ticket #" + id + " is currently being processed and cannot be deleted.");
            return "redirect:/tickets";
        }
        emailTicketRepository.delete(ticket);
        log.info("Ticket {} soft-deleted", id);
        auditService.record(id, TicketAuditService.ACTION_DELETE, null);
        redirectAttributes.addFlashAttribute("successMessage", "Ticket #" + id + " deleted.");
        return "redirect:/tickets";
    }

    /**
     * POST /tickets/{id}/close — operator closes a ticket (reply sent / question resolved).
     *
     * @param id ticket identifier
     * @return redirect to the ticket list
     */
    @Transactional
    @PostMapping("/tickets/{id}/close")
    public String closeTicket(@PathVariable Long id) {
        emailTicketRepository.findById(id).ifPresent(ticket -> {
            if (ticket.getStatus() == TicketStatus.AUTO_SENT) {
                return; // terminal — "closing" would erase the auto-sent record
            }
            ticket.setStatus(TicketStatus.CLOSED);
            ticket.setUpdatedAt(LocalDateTime.now());
            emailTicketRepository.save(ticket);
            auditService.record(id, TicketAuditService.ACTION_CLOSE, null);
        });
        return "redirect:/tickets";
    }

    /**
     * POST /tickets/{id}/draft — operator edited the reply draft.
     *
     * Action parameter {@code action}:
     * — {@code "save"}  — save only and return to the detail page
     * — {@code "close"} — save and close the ticket (without sending an email)
     * — {@code "send"}  — save, send the email to the sender, close the ticket
     *
     * A closed ticket cannot be edited — redirects to the ticket list without changes.
     *
     * @param id                 ticket identifier
     * @param draftReply         edited draft text
     * @param action             desired action (save / close / send)
     * @param redirectAttributes flash messages (success / error)
     * @return redirect URL
     */
    @Transactional
    @PostMapping("/tickets/{id}/draft")
    public String updateDraft(@PathVariable Long id,
                              @RequestParam("draftReply") String draftReply,
                              @RequestParam(name = "action", defaultValue = "save") String action,
                              RedirectAttributes redirectAttributes) {
        EmailTicket ticket = emailTicketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ticket not found: " + id));

        // CLOSED and AUTO_SENT are terminal — the reply already went out (or the ticket was
        // resolved); editing or re-sending the draft is not allowed.
        if (ticket.getStatus() == TicketStatus.CLOSED || ticket.getStatus() == TicketStatus.AUTO_SENT) {
            return "redirect:/tickets/" + id;
        }

        ticket.setDraftReply(draftReply);

        if ("send".equalsIgnoreCase(action)) {
            if (!sendRateLimiter.tryAcquire(id)) {
                log.warn("Send rate-limited for ticket {} — within {}s cooldown", id, sendRateLimiter.getCooldown().toSeconds());
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Please wait a few seconds before sending again.");
                return "redirect:/tickets/" + id;
            }
            try {
                emailReplyService.sendReply(ticket);
                ticket.setStatus(TicketStatus.CLOSED);
                log.info("Ticket {} draft sent to {} and closed", id, ticket.getSenderEmail());
                auditService.record(id, TicketAuditService.ACTION_SEND, "to=" + ticket.getSenderEmail());
                // Phase 2.5: a sent RFQ quote arms a one-shot follow-up (guarded inside the service —
                // non-RFQ and proactive tickets are ignored).
                quoteFollowUpService.armFromTicket(ticket);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Reply sent to " + ticket.getSenderEmail());
            } catch (Exception e) {
                log.error("Failed to send reply for ticket {}: {}", id, e.getMessage(), e);
                auditService.record(id, TicketAuditService.ACTION_SEND, "FAILED: " + e.getMessage());
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Failed to send email: " + e.getMessage());
                return "redirect:/tickets/" + id;
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

        if ("send".equalsIgnoreCase(action) || "close".equalsIgnoreCase(action)) {
            return "redirect:/tickets";
        }
        return "redirect:/tickets/" + id;
    }
}
