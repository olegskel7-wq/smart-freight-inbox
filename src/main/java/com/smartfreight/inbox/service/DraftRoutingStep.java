package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.TicketType;
import org.springframework.stereotype.Component;

/**
 * Where a DRAFT_READY ticket goes, for {@link TicketProcessingService}: the shipment-watch subscription,
 * the auto-send decision and the Gmail draft. Runs inside the orchestrator's transaction, after the
 * DRAFT_READY save; the SMTP send itself stays with the caller, after the commit.
 */
@Component
class DraftRoutingStep {

    private final AutoSendService autoSendService;
    private final ShipmentWatchService shipmentWatchService;
    private final GmailDraftService gmailDraftService;

    DraftRoutingStep(AutoSendService autoSendService,
                     ShipmentWatchService shipmentWatchService,
                     GmailDraftService gmailDraftService) {
        this.autoSendService = autoSendService;
        this.shipmentWatchService = shipmentWatchService;
        this.gmailDraftService = gmailDraftService;
    }

    /**
     * @return whether the ticket qualifies for auto-send — the caller sends after the transaction commits
     */
    boolean route(EmailTicket ticket, TrackedItem item) {
        // A STATUS_UPDATE enquiry grounded in real backend data becomes a proactive
        // subscription — StatusWatchScheduler keeps this customer posted about this shipment
        // until it is delivered. Recorded unconditionally (cheap); the scheduler is gated by
        // feature.status-update-automation, so enabling the flag activates recent enquiries at once.
        if (ticket.getTicketType() == TicketType.STATUS_UPDATE
                && ticket.getExtractedIdentifier() != null
                && item != null) {
            shipmentWatchService.upsertFromTicket(ticket, item);
        }

        // The auto-send decision is made here (one settings read, one log line), but the SMTP
        // call belongs to the caller — after this transaction commits. An eligible ticket also
        // skips the Gmail draft: it is about to be answered, a lingering draft would be noise.
        boolean autoSendEligible = autoSendService.shouldAutoSend(ticket);
        if (!autoSendEligible) {
            gmailDraftService.saveDraft(ticket);
        }
        return autoSendEligible;
    }
}
