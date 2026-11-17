package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.util.EmailAddressUtils;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import com.smartfreight.inbox.webhook.WebhookPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * The operator's reply to a ticket: save, close or send the draft, and pick the on-file document that
 * goes out with it. Each method takes a ticket the caller already loaded and runs in one transaction
 * (it joins the endpoint's), so the send, its audit row, the quote follow-up, the captured correction and
 * the webhooks commit together.
 */
@Service
public class TicketReplyService {

    private static final Logger log = LoggerFactory.getLogger(TicketReplyService.class);

    /** What {@link #applyDraft} did. */
    public enum DraftOutcome {
        /** CLOSED / AUTO_SENT: nothing changed. */
        TERMINAL,
        /** Saved, closed or sent, and the ticket saved. */
        DONE,
        /** A send inside the per-ticket cooldown: not sent, not saved. */
        RATE_LIMITED,
        /** SMTP failed: audited, not saved. */
        SEND_FAILED
    }

    /** What {@link #attachDocument} did. */
    public enum AttachOutcome { TERMINAL, DONE, DOCUMENT_NOT_FOUND }

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;
    private final TicketAuditService auditService;
    private final SendRateLimiter sendRateLimiter;
    private final QuoteFollowUpService quoteFollowUpService;
    private final DocumentService documentService;
    private final FeedbackLoopService feedbackLoopService;
    private final WebhookEventPublisher webhookEventPublisher;
    private final SlaService slaService;

    public TicketReplyService(EmailTicketRepository emailTicketRepository,
                              EmailReplyService emailReplyService,
                              TicketAuditService auditService,
                              SendRateLimiter sendRateLimiter,
                              QuoteFollowUpService quoteFollowUpService,
                              DocumentService documentService,
                              FeedbackLoopService feedbackLoopService,
                              WebhookEventPublisher webhookEventPublisher,
                              SlaService slaService) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.auditService = auditService;
        this.sendRateLimiter = sendRateLimiter;
        this.quoteFollowUpService = quoteFollowUpService;
        this.documentService = documentService;
        this.feedbackLoopService = feedbackLoopService;
        this.webhookEventPublisher = webhookEventPublisher;
        this.slaService = slaService;
    }

    private static boolean isTerminal(EmailTicket ticket) {
        return ticket.getStatus() == TicketStatus.CLOSED || ticket.getStatus() == TicketStatus.AUTO_SENT;
    }

    /**
     * Applies the operator's draft: {@code send} (case-insensitive) mails it and closes the ticket,
     * {@code close} closes it without mailing, anything else (or none) saves it as DRAFT_READY.
     *
     * @param action     save | close | send; null = save
     * @param draftReply the operator's text, set on the ticket before anything else
     * @param signedAs   the sending operator's display name for the signature (drafts are unsigned —
     *                   see {@link ReplySignatureService})
     */
    @Transactional
    public DraftOutcome applyDraft(EmailTicket ticket, String action, String draftReply, String signedAs) {
        if (isTerminal(ticket)) {
            return DraftOutcome.TERMINAL;
        }
        Long id = ticket.getId();
        String verb = action == null ? "save" : action;
        ticket.setDraftReply(draftReply);

        if ("send".equalsIgnoreCase(verb)) {
            if (!sendRateLimiter.tryAcquire(id)) {
                log.warn("Send rate-limited for ticket {} — within {}s cooldown",
                        id, sendRateLimiter.getCooldown().toSeconds());
                return DraftOutcome.RATE_LIMITED;
            }
            try {
                emailReplyService.sendReply(ticket, signedAs);
                slaService.markBreachedIfOverdue(ticket, LocalDateTime.now());
                ticket.setStatus(TicketStatus.CLOSED);
                log.info("Ticket {} draft sent and closed (API)", id);
                auditService.record(id, TicketAuditService.ACTION_SEND, "to=" + ticket.getSenderEmail());
                quoteFollowUpService.armFromTicket(ticket);
                feedbackLoopService.captureIfEdited(ticket);
                emitReplyWebhooks(ticket);
            } catch (Exception e) {
                // SMTP messages carry the recipient: mask it, and no stack trace (it repeats the message).
                log.error("Failed to send reply for ticket {} (API): {}: {}", id, e.getClass().getSimpleName(),
                        EmailAddressUtils.maskAddresses(e.getMessage()));
                auditService.record(id, TicketAuditService.ACTION_SEND, "FAILED: " + e.getMessage());
                return DraftOutcome.SEND_FAILED;
            }
        } else if ("close".equalsIgnoreCase(verb)) {
            slaService.markBreachedIfOverdue(ticket, LocalDateTime.now());
            ticket.setStatus(TicketStatus.CLOSED);
            auditService.record(id, TicketAuditService.ACTION_CLOSE, null);
        } else {
            ticket.setStatus(TicketStatus.DRAFT_READY);
            auditService.record(id, TicketAuditService.ACTION_DRAFT_EDIT, null);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        return DraftOutcome.DONE;
    }

    /**
     * Attaches an on-file document of the current tenant to the reply, or detaches when
     * {@code documentId} is null.
     */
    @Transactional
    public AttachOutcome attachDocument(EmailTicket ticket, Long documentId) {
        if (isTerminal(ticket)) {
            return AttachOutcome.TERMINAL;
        }
        if (documentId == null) {
            ticket.setAttachedDocumentId(null);
        } else {
            Optional<TenantDocument> doc = documentService.find(documentId);
            if (doc.isEmpty()) {
                return AttachOutcome.DOCUMENT_NOT_FOUND;
            }
            ticket.setAttachedDocumentId(doc.get().getId());
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        return AttachOutcome.DONE;
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
}
