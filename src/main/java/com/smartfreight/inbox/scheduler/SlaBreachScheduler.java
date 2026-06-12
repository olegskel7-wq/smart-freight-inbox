package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.SlaService;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Marks open tickets whose SLA deadline has passed (Phase 1.5).
 *
 * One bulk UPDATE per run, same pattern as {@link StuckTicketSweeper}: idempotent
 * (only rows with slaBreached = false are touched), so concurrent instances or
 * overlapping runs are harmless.
 *
 * CLOSED and IRRELEVANT tickets are excluded — a ticket answered in time never
 * becomes breached, and spam carries no SLA. DRAFT_READY and FAILED still count
 * as open: the SLA promise is a *reply*, and an unsent draft is not a reply.
 *
 * Once set, the flag is never cleared (including by a later reply) — breaches are
 * a historical record for SLA reporting.
 */
@Component
public class SlaBreachScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachScheduler.class);

    /** Statuses where the SLA clock no longer applies. */
    static final List<TicketStatus> SLA_EXEMPT_STATUSES =
            List.of(TicketStatus.CLOSED, TicketStatus.IRRELEVANT);

    private final EmailTicketRepository emailTicketRepository;
    private final SlaService slaService;
    private final TransactionTemplate transactionTemplate;
    private final Counter breachedCounter;

    public SlaBreachScheduler(EmailTicketRepository emailTicketRepository,
                              SlaService slaService,
                              TransactionTemplate transactionTemplate,
                              MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.slaService = slaService;
        this.transactionTemplate = transactionTemplate;
        this.breachedCounter = Counter.builder("smartfreight_sla_breached_total")
                .description("Open tickets that passed their SLA deadline without a reply")
                .register(meterRegistry);
    }

    /**
     * Every minute by default — breach marking feeds the UI badge and metrics, so a
     * sub-minute lag is acceptable and one indexed bulk UPDATE per run costs nothing.
     */
    @Scheduled(fixedDelayString = "${sla.breach-check-interval-ms:60000}", initialDelay = 60_000)
    public void markBreachedTickets() {
        if (!slaService.isEnabled()) {
            return;
        }

        // Phase 2.2: SYSTEM tenant state — one bulk UPDATE marks breaches across all
        // tenants. Context wraps the transaction (see RetryScheduler for why).
        int breached = TenantContext.callAsSystem(() -> transactionTemplate.execute(tx ->
                emailTicketRepository.markSlaBreached(LocalDateTime.now(), SLA_EXEMPT_STATUSES)));

        if (breached > 0) {
            breachedCounter.increment(breached);
            log.warn("SlaBreachScheduler: {} ticket(s) passed their SLA deadline without a reply", breached);
        }
    }
}
