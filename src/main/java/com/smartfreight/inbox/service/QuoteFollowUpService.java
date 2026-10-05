package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.QuoteFollowUpRepository;
import com.smartfreight.inbox.util.EmailAddressUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Maintains proactive quote-follow-up subscriptions (Phase 2.5).
 *
 * <p>The sent quote is the subscription: when an RFQ reply is actually sent (operator send →
 * CLOSED, or auto-send → AUTO_SENT), {@link #armFromTicket} records (or refreshes) a
 * {@link QuoteFollowUp}. {@code QuoteFollowUpScheduler} then drives the single nudge once the
 * customer has stayed silent for the configured delay. Runs inside the ticket's tenant context,
 * so {@code @TenantId} stamps the owning tenant automatically.
 *
 * <p>Armed unconditionally (regardless of {@code feature.quote-followup}) — the row is cheap and
 * the scheduler is what's gated, so enabling the flag activates recently-quoted tickets at once,
 * mirroring how Phase 2.4 records shipment watches.
 */
@Service
public class QuoteFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(QuoteFollowUpService.class);

    private final QuoteFollowUpRepository followUpRepository;

    public QuoteFollowUpService(QuoteFollowUpRepository followUpRepository) {
        this.followUpRepository = followUpRepository;
    }

    /**
     * Records or refreshes a follow-up from a just-sent RFQ reply.
     *
     * <p>Only genuine inbound RFQ replies arm a follow-up: proactive tickets (our own status
     * updates and quote nudges are {@code proactive=true}) and non-RFQ types are ignored, which
     * also prevents a nudge from arming another nudge.
     *
     * @param ticket the RFQ ticket whose quote reply was just sent
     */
    public void armFromTicket(EmailTicket ticket) {
        if (ticket.getTicketType() != TicketType.RFQ || ticket.isProactive()) {
            return;
        }
        String email = EmailAddressUtils.extract(ticket.getSenderEmail());
        if (email == null || email.isBlank()) {
            return;
        }

        QuoteFollowUp followUp = followUpRepository.findByTicketId(ticket.getId())
                .orElseGet(() -> {
                    QuoteFollowUp f = new QuoteFollowUp();
                    f.setTicketId(ticket.getId());
                    return f;
                });
        followUp.setCustomerEmail(email);
        followUp.setQuoteSentAt(LocalDateTime.now());
        followUp.setActive(true);
        followUp.setUpdatedAt(LocalDateTime.now());
        followUpRepository.save(followUp);
        log.info("Quote follow-up armed: ticket={}", ticket.getId());
    }
}
