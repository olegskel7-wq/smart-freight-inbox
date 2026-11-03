package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TicketDetailDto;
import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.service.EmailReplyService;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.QuoteFollowUpService;
import com.smartfreight.inbox.service.ReplySignatureService;
import com.smartfreight.inbox.service.SendRateLimiter;
import com.smartfreight.inbox.service.TicketAuditService;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import com.smartfreight.inbox.webhook.WebhookPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;

import static com.smartfreight.inbox.controller.TicketDetailAssembler.isTerminal;

/**
 * The reply to a ticket: the operator edits, saves, closes or sends the draft and picks the
 * on-file document that goes out with it. The draft/send orchestration lives here.
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketReplyController {

    private static final Logger log = LoggerFactory.getLogger(TicketReplyController.class);

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;
    private final TicketAuditService auditService;
    private final SendRateLimiter sendRateLimiter;
    private final QuoteFollowUpService quoteFollowUpService;
    private final TenantDocumentRepository documentRepository;
    private final FeedbackLoopService feedbackLoopService;
    private final WebhookEventPublisher webhookEventPublisher;
    private final TicketDetailAssembler details;

    public TicketReplyController(EmailTicketRepository emailTicketRepository,
                                 EmailReplyService emailReplyService,
                                 TicketAuditService auditService,
                                 SendRateLimiter sendRateLimiter,
                                 QuoteFollowUpService quoteFollowUpService,
                                 TenantDocumentRepository documentRepository,
                                 FeedbackLoopService feedbackLoopService,
                                 WebhookEventPublisher webhookEventPublisher,
                                 TicketDetailAssembler details) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.auditService = auditService;
        this.sendRateLimiter = sendRateLimiter;
        this.quoteFollowUpService = quoteFollowUpService;
        this.documentRepository = documentRepository;
        this.feedbackLoopService = feedbackLoopService;
        this.webhookEventPublisher = webhookEventPublisher;
        this.details = details;
    }

    /** Request body for {@code POST /api/tickets/{id}/draft}: action = save | close | send. */
    public record DraftRequest(String action, String draftReply) {}

    /** Request body for {@code POST /api/tickets/{id}/attach-document}: null id detaches. */
    public record AttachRequest(Long documentId) {}

    /**
     * POST /api/tickets/{id}/draft — save / close / send the draft. Returns the updated detail;
     * 429 when send is rate-limited, 502 when SMTP fails. CLOSED/AUTO_SENT are terminal →
     * unchanged detail.
     */
    @Transactional
    @PostMapping("/{id}/draft")
    public ResponseEntity<TicketDetailDto> updateDraft(@PathVariable Long id,
                                                       @RequestBody DraftRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        EmailTicket ticket = details.requireTicket(id);
        if (isTerminal(ticket)) {
            return ResponseEntity.ok(details.buildDetail(ticket));
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
                // Signed as the sending operator (drafts are unsigned — see ReplySignatureService).
                emailReplyService.sendReply(ticket, ReplySignatureService.displayName(jwt));
                ticket.setStatus(TicketStatus.CLOSED);
                log.info("Ticket {} draft sent and closed (API)", id);
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
        return ResponseEntity.ok(details.buildDetail(ticket));
    }

    /**
     * Queues outbound webhook deliveries after a reply is sent — {@code reply.sent} for
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

    /**
     * POST /api/tickets/{id}/attach-document — attach an on-file document to the reply, or detach
     * when {@code documentId} is null. Terminal tickets → unchanged detail.
     */
    @Transactional
    @PostMapping("/{id}/attach-document")
    public TicketDetailDto attachDocument(@PathVariable Long id, @RequestBody AttachRequest request) {
        EmailTicket ticket = details.requireTicket(id);
        if (isTerminal(ticket)) {
            return details.buildDetail(ticket);
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
        return details.buildDetail(ticket);
    }
}
