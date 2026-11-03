package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import com.smartfreight.inbox.webhook.WebhookEventType;
import com.smartfreight.inbox.webhook.WebhookPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pipeline step 0 of {@link TicketProcessingService}: relevance + type classification (Qwen 2.5)
 * and what the verdict sets on the ticket — type, smart-routing signals, the per-type SLA window,
 * escalation routing and the {@code booking.confirmed} webhook. Classification happens here, off the
 * IMAP thread.
 *
 * The orchestrator decides what an irrelevant verdict means for the ticket's status. Runs inside the
 * orchestrator's transaction (the webhook delivery is queued atomically with the verdict); an
 * open-breaker exception propagates so the ticket is parked.
 */
@Component
class TicketClassificationStep {

    private static final Logger log = LoggerFactory.getLogger(TicketClassificationStep.class);

    private final EmailClassificationService emailClassificationService;
    private final SlaService slaService;
    private final SettingsService settingsService;
    private final TicketAuditService ticketAuditService;
    private final WebhookEventPublisher webhookEventPublisher;

    TicketClassificationStep(EmailClassificationService emailClassificationService,
                             SlaService slaService,
                             SettingsService settingsService,
                             TicketAuditService ticketAuditService,
                             WebhookEventPublisher webhookEventPublisher) {
        this.emailClassificationService = emailClassificationService;
        this.slaService = slaService;
        this.settingsService = settingsService;
        this.ticketAuditService = ticketAuditService;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    /**
     * In typed mode the retry path re-enters classification when ticketType
     * is still null — such a ticket never got a verdict (it was parked by an open
     * breaker before step 0 completed, or swept out of RECEIVED). This is a first
     * classification, not a re-judgement: tickets the operator sent back for a redraft
     * carry their type and are not re-classified. Binary mode persists no verdict
     * marker, so there the retry path keeps its historical fail-open skip.
     */
    boolean needsClassification(EmailTicket ticket) {
        return ticket.getStatus() == TicketStatus.RECEIVED
                || (ticket.getTicketType() == null && emailClassificationService.isTypedMode());
    }

    /**
     * Classifies the ticket and, when it is relevant, applies the verdict to it.
     *
     * @return {@code false} for an irrelevant ticket (only its SLA deadline is dropped), {@code true} otherwise
     */
    boolean classify(EmailTicket ticket, String aiInput) {
        // The same call also assigns the business type (RFQ/STATUS_UPDATE/…).
        EmailClassificationService.ClassificationResult classification =
                emailClassificationService.classify(ticket.getSubject(), aiInput);
        if (!classification.relevant()) {
            // Spam carries no SLA — drop the provisional deadline set at ingest.
            ticket.setSlaDeadlineAt(null);
            return false;
        }
        // Persisted together with the orchestrator's PROCESSING transition — no extra UPDATE.
        ticket.setTicketType(classification.type());
        // Smart-routing signals from the same classification call.
        ticket.setUrgency(classification.urgency());
        ticket.setSentimentNegative(classification.sentimentNegative());
        // Refine the provisional (GENERAL) deadline to the per-type window.
        // Still anchored to createdAt — the customer's clock started at receipt.
        // An escalated ticket (HIGH urgency / negative tone) gets a tighter window.
        if (slaService.isEnabled()) {
            ticket.setSlaDeadlineAt(slaService.deadlineFor(classification.type(),
                    classification.isEscalated(), ticket.getCreatedAt()));
        }
        // Route an escalated, still-unclaimed ticket to the configured escalation operator.
        applyEscalationRouting(ticket, classification);
        // The customer is booking — notify the CRM. Fires once: a typed ticket is not
        // re-classified (the retry path only re-enters this step when ticketType is still null), and
        // this runs inside the pipeline transaction so the delivery is queued atomically with the verdict.
        if (classification.type() == TicketType.BOOKING_CONFIRM) {
            webhookEventPublisher.publish(WebhookEventType.BOOKING_CONFIRMED, ticket.getTenantId(),
                    WebhookPayloads.ticketData(ticket));
        }
        return true;
    }

    /**
     * Auto-assigns an escalated ticket to the tenant's configured escalation operator.
     *
     * Routing is deliberately deterministic, not LLM-chosen: the classifier only flags HIGH urgency /
     * negative tone (it does not know the tenant's operators), and a tenant opts in by setting
     * {@link TicketProcessingService#SETTING_ESCALATION_ASSIGNEE} on {@code /settings}. We only ever
     * claim an UNassigned ticket, so a manual assignment is never overwritten. With no assignee
     * configured the ticket simply stays unclaimed — escalation still tightened the SLA and floats it
     * to the top. Mutating {@code assigneeUserId} fires the existing {@code @PostUpdate} live event for free.
     */
    private void applyEscalationRouting(EmailTicket ticket, EmailClassificationService.ClassificationResult classification) {
        if (!classification.isEscalated() || ticket.getAssigneeUserId() != null) {
            return;
        }
        String target = settingsService.getString(TicketProcessingService.SETTING_ESCALATION_ASSIGNEE, "").trim();
        if (target.isEmpty()) {
            return;
        }
        ticket.setAssigneeUserId(target);
        ticketAuditService.record(ticket.getId(), TicketAuditService.ACTION_ASSIGN, "auto-escalation to=" + target);
        log.info("Auto-assigned escalated ticket {} to escalation operator {}", ticket.getId(), target);
    }
}
