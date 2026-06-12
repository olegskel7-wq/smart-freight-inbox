package com.smartfreight.inbox.startup;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup recovery for tickets that were stuck in {@link TicketStatus#PROCESSING}
 * when the previous JVM was killed mid-pipeline.
 *
 * Without this recovery such tickets would remain {@code PROCESSING} forever:
 * {@link com.smartfreight.inbox.scheduler.RetryScheduler} only picks up
 * {@code RETRY_LATER}, and there is no operator UI flow to "unstick" a ticket.
 *
 * The recovery flips every {@code PROCESSING} row to {@code RETRY_LATER} on
 * {@link ApplicationReadyEvent}, after which {@code RetryScheduler} naturally
 * resumes processing on its normal cadence.
 *
 * @{@code retryCount} is intentionally NOT reset — a ticket that crashed the JVM
 * may keep crashing it, and the existing MAX_RETRIES guard protects us from
 * infinite loops on the same poison-pill input.
 */
@Component
public class StuckTicketRecovery {

    private static final Logger log = LoggerFactory.getLogger(StuckTicketRecovery.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TransactionTemplate transactionTemplate;

    public StuckTicketRecovery(EmailTicketRepository emailTicketRepository,
                               TransactionTemplate transactionTemplate) {
        this.emailTicketRepository = emailTicketRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Fires once when the application context is fully initialised
     * (after Flyway, after JPA, before incoming traffic).
     *
     * Phase 2.2: SYSTEM tenant state — recovery spans all tenants in one bulk UPDATE.
     * Context wraps the transaction (see RetryScheduler for why not @Transactional).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckTickets() {
        int reset = TenantContext.callAsSystem(() -> transactionTemplate.execute(tx ->
                emailTicketRepository.bulkTransitionStatus(
                        TicketStatus.PROCESSING, TicketStatus.RETRY_LATER)));

        if (reset > 0) {
            log.warn("Startup recovery: {} ticket(s) were stuck in PROCESSING and moved to RETRY_LATER", reset);
        } else {
            log.info("Startup recovery: no stuck PROCESSING tickets");
        }
    }
}
