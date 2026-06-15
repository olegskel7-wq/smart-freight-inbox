package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.PageDto;
import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.controller.dto.TicketSummaryDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * JSON API backing the React {@code /tickets} pages (Phase 3.2). It mirrors the behaviour of
 * {@link TicketViewController} (the Thymeleaf UI) but speaks DTOs over {@code /api/tickets} and is
 * secured by the stateless resource-server chain (bearer JWT). The two controllers run side by
 * side during the migration; the draft/send orchestration is duplicated here on purpose so the
 * live Thymeleaf path stays untouched — it collapses to this one when Thymeleaf is removed.
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

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;
    private final TicketAuditService auditService;
    private final SendRateLimiter sendRateLimiter;
    private final QuoteFollowUpService quoteFollowUpService;
    private final TenantDocumentRepository documentRepository;
    private final FeedbackLoopService feedbackLoopService;

    public TicketApiController(EmailTicketRepository emailTicketRepository,
                               EmailReplyService emailReplyService,
                               TicketAuditService auditService,
                               SendRateLimiter sendRateLimiter,
                               QuoteFollowUpService quoteFollowUpService,
                               TenantDocumentRepository documentRepository,
                               FeedbackLoopService feedbackLoopService) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.auditService = auditService;
        this.sendRateLimiter = sendRateLimiter;
        this.quoteFollowUpService = quoteFollowUpService;
        this.documentRepository = documentRepository;
        this.feedbackLoopService = feedbackLoopService;
    }

    /** Request body for {@code POST /api/tickets/{id}/draft}: action = save | close | send. */
    public record DraftRequest(String action, String draftReply) {}

    /** Request body for {@code POST /api/tickets/{id}/attach-document}: null id detaches. */
    public record AttachRequest(Long documentId) {}

    /** GET /api/tickets — paginated, sorted, optionally status-filtered list. */
    @GetMapping
    public PageDto<TicketSummaryDto> list(
            @RequestParam(name = "sort", defaultValue = DEFAULT_SORT) String sort,
            @RequestParam(name = "dir", defaultValue = "desc") String dir,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        String safeSort = SORTABLE_COLUMNS.contains(sort) ? sort : DEFAULT_SORT;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, safeSort));
        TicketStatus statusFilter = parseStatusOrNull(status);
        Page<EmailTicket> ticketPage = (statusFilter != null)
                ? emailTicketRepository.findByStatus(statusFilter, pageable)
                : emailTicketRepository.findAll(pageable);

        return PageDto.of(ticketPage, TicketSummaryDto::from);
    }

    /** GET /api/tickets/{id} — full ticket detail (404 if missing). */
    @GetMapping("/{id}")
    public TicketDetailDto detail(@PathVariable Long id) {
        return buildDetail(requireTicket(id));
    }

    /**
     * POST /api/tickets/{id}/draft — save / close / send the draft (mirrors
     * {@link TicketViewController#updateDraft}). Returns the updated detail; 429 when send is
     * rate-limited, 502 when SMTP fails. CLOSED/AUTO_SENT are terminal → unchanged detail.
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
        return TicketDetailDto.from(ticket, attached, available);
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
