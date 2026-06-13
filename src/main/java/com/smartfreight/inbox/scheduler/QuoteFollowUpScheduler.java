package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.QuoteFollowUpRepository;
import com.smartfreight.inbox.service.AiDraftingService;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proactive quote-follow-up poller (Phase 2.5).
 *
 * <p>The reactive pipeline answers an RFQ and stops; this scheduler chases the silence. For every
 * active {@link QuoteFollowUp} (armed when a quote reply was actually sent) it waits
 * {@code quotefollowup.delay-hours} and then, if the customer has stayed silent, emails one gentle
 * "just following up" nudge — a single shot, after which the follow-up is deactivated.
 *
 * <p>Tenant model and send policy mirror {@link StatusWatchScheduler}: phase A reads every tenant's
 * active follow-ups in the SYSTEM state; phase B processes each tenant inside its own
 * {@link TenantContext} scope (per-tenant flag, Ollama endpoint, RLS, ticket stamping). The nudge is
 * delivered as an outbound {@link EmailTicket} routed through the {@link AutoSendService} gate
 * (Phase 1.6) — auto-sent only if the operator allow-listed RFQ for auto-send, otherwise left as a
 * DRAFT_READY for review. The SMTP send happens AFTER the DB transaction commits.
 *
 * <p>Reply detection is sender-based: a fresh inbound from the same address since the quote went out
 * satisfies the follow-up (no nudge). It fails safe toward not nudging someone who already engaged —
 * thread-header matching is deferred (the ingestion does not store References today).
 */
@Component
public class QuoteFollowUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteFollowUpScheduler.class);

    /** Gate — like feature.auto-send it emails real customers, so the code default is OFF. */
    public static final String FEATURE_KEY = "feature.quote-followup";

    /** Hours of customer silence after the quote was sent before the single nudge fires (V24 seed). */
    public static final String KEY_DELAY_HOURS = "quotefollowup.delay-hours";
    public static final long DEFAULT_DELAY_HOURS = 48L;

    private final QuoteFollowUpRepository followUpRepository;
    private final EmailTicketRepository emailTicketRepository;
    private final AiDraftingService aiDraftingService;
    private final AutoSendService autoSendService;
    private final SettingsService settingsService;
    private final TransactionTemplate transactionTemplate;
    private final Counter sentCounter;
    private final Counter draftedCounter;

    public QuoteFollowUpScheduler(QuoteFollowUpRepository followUpRepository,
                                  EmailTicketRepository emailTicketRepository,
                                  AiDraftingService aiDraftingService,
                                  AutoSendService autoSendService,
                                  SettingsService settingsService,
                                  TransactionTemplate transactionTemplate,
                                  MeterRegistry meterRegistry) {
        this.followUpRepository = followUpRepository;
        this.emailTicketRepository = emailTicketRepository;
        this.aiDraftingService = aiDraftingService;
        this.autoSendService = autoSendService;
        this.settingsService = settingsService;
        this.transactionTemplate = transactionTemplate;
        this.sentCounter = Counter.builder("smartfreight_quote_followups_total")
                .description("Proactive quote follow-ups emitted")
                .tag("result", "auto_sent")
                .register(meterRegistry);
        this.draftedCounter = Counter.builder("smartfreight_quote_followups_total")
                .description("Proactive quote follow-ups emitted")
                .tag("result", "drafted")
                .register(meterRegistry);
    }

    /**
     * Polls all active follow-ups and emits one nudge per quote whose delay has elapsed without a
     * customer reply. Schedule mirrors {@link StatusWatchScheduler}: a fixed pause between runs and
     * a startup delay. The interval is configurable ({@code quotefollowup.poll-interval-ms}, default
     * 2 minutes).
     */
    @Scheduled(fixedDelayString = "${quotefollowup.poll-interval-ms:120000}", initialDelay = 60_000)
    public void pollFollowUps() {
        // Phase A — SYSTEM state: one read across every tenant's active follow-ups.
        List<QuoteFollowUp> followUps = TenantContext.callAsSystem(() ->
                transactionTemplate.execute(tx -> new ArrayList<>(followUpRepository.findByActiveTrue())));

        if (followUps == null || followUps.isEmpty()) {
            return;
        }

        // Phase B — per tenant: each follow-up processed inside its owning tenant's scope.
        Map<Long, List<Long>> idsByTenant = followUps.stream()
                .collect(Collectors.groupingBy(QuoteFollowUp::getTenantId,
                        Collectors.mapping(QuoteFollowUp::getId, Collectors.toList())));

        idsByTenant.forEach((tenantId, ids) ->
                TenantContext.runAs(tenantId, () -> processTenant(ids)));
    }

    /** Processes one tenant's follow-ups in its context. Skipped entirely when the flag is off. */
    private void processTenant(List<Long> followUpIds) {
        if (!settingsService.isFeatureEnabled(FEATURE_KEY, false)) {
            return;
        }
        long delayHours = delayHours();

        for (Long followUpId : followUpIds) {
            try {
                NudgeOutcome outcome =
                        transactionTemplate.execute(tx -> prepareNudge(followUpId, delayHours));
                if (outcome == null) {
                    continue;
                }
                // After the DB transaction committed (same ordering as the reactive pipeline):
                // an SMTP failure now leaves a safe DRAFT_READY ticket, never a half-sent nudge.
                if (outcome.eligible()) {
                    autoSendService.autoSend(outcome.ticketId());
                    sentCounter.increment();
                } else {
                    draftedCounter.increment();
                }
            } catch (CallNotPermittedException e) {
                // The tenant's Ollama breaker is open — drafting was not attempted, the transaction
                // rolled back (no ticket, follow-up not advanced). Retry next poll.
                log.warn("Ollama breaker open — skipping quote follow-up {} this run", followUpId);
            } catch (Exception e) {
                log.error("Failed to process quote follow-up {}: {}", followUpId, e.getMessage());
            }
        }
    }

    /**
     * In-transaction work for one follow-up: check the delay and the customer's silence, and if a
     * nudge is due, draft + persist a proactive ticket and deactivate the follow-up (single shot).
     * Returns null when there is nothing to send (not due yet, or the customer already replied).
     */
    private NudgeOutcome prepareNudge(Long followUpId, long delayHours) {
        QuoteFollowUp followUp = followUpRepository.findById(followUpId).orElse(null);
        if (followUp == null || !followUp.isActive()) {
            return null;
        }

        // Not due yet — leave it active and revisit next poll.
        if (LocalDateTime.now().isBefore(followUp.getQuoteSentAt().plusHours(delayHours))) {
            return null;
        }

        // Satisfied — the customer engaged since the quote went out, so no nudge is needed.
        if (emailTicketRepository.existsInboundReplyFrom(
                followUp.getCustomerEmail().toLowerCase(), followUp.getQuoteSentAt())) {
            followUp.setActive(false);
            followUp.setUpdatedAt(LocalDateTime.now());
            followUpRepository.save(followUp);
            log.info("Quote follow-up {} satisfied — customer replied, no nudge", followUpId);
            return null;
        }

        EmailTicket original = emailTicketRepository.findById(followUp.getTicketId()).orElse(null);
        if (original == null) {
            // Quoted ticket gone (deleted) — nothing to follow up on. Close the subscription.
            followUp.setActive(false);
            followUp.setUpdatedAt(LocalDateTime.now());
            followUpRepository.save(followUp);
            return null;
        }

        // May throw CallNotPermittedException (breaker open) — propagated to roll the TX back.
        String draft = aiDraftingService.generateQuoteFollowUp(original);

        String subject = original.getSubject() != null ? original.getSubject() : "your enquiry";
        EmailTicket ticket = EmailTicket.builder()
                // Synthetic Message-ID: the column is NOT NULL with a per-tenant unique constraint,
                // and a proactive nudge belongs to no inbound thread.
                .messageId("<followup-" + UUID.randomUUID() + "@smartfreight>")
                .senderEmail(followUp.getCustomerEmail())
                .subject("Following up: " + subject)
                .body("Proactive quote follow-up for ticket " + followUp.getTicketId())
                .extractedIdentifier(original.getExtractedIdentifier())
                .ticketType(TicketType.RFQ)
                .draftReply(draft)
                // type specific + identifier maybe present + no item lookup, no rate. RFQ is not in
                // the default auto-send allowlist, so the nudge defaults to an operator draft anyway.
                .aiConfidence(autoSendService.confidenceFor(
                        TicketType.RFQ, original.getExtractedIdentifier() != null, false, false))
                .status(TicketStatus.DRAFT_READY)
                .proactive(true)
                .build();
        emailTicketRepository.save(ticket);

        boolean eligible = autoSendService.shouldAutoSend(ticket);

        // Single nudge — the follow-up is done whether it auto-sends or is left as a draft.
        followUp.setActive(false);
        followUp.setUpdatedAt(LocalDateTime.now());
        followUpRepository.save(followUp);

        log.info("Quote follow-up for ticket {}, nudge ticket {} — {}",
                followUp.getTicketId(), ticket.getId(), eligible ? "auto-sending" : "left as draft");
        return new NudgeOutcome(ticket.getId(), eligible);
    }

    /** Delay window from settings, defaulting and clamping malformed/negative values. */
    long delayHours() {
        String raw = settingsService.getString(KEY_DELAY_HOURS, String.valueOf(DEFAULT_DELAY_HOURS));
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("Malformed {} value '{}' — using default {}", KEY_DELAY_HOURS, raw, DEFAULT_DELAY_HOURS);
            return DEFAULT_DELAY_HOURS;
        }
    }

    /** Result of preparing one nudge: the saved ticket and whether it should auto-send. */
    private record NudgeOutcome(Long ticketId, boolean eligible) {
    }
}
