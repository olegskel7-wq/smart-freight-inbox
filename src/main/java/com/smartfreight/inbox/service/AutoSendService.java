package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Auto-send threshold (Phase 1.6): sends the AI draft without operator review when the
 * pipeline's confidence in it is high enough.
 *
 * <p>Confidence is a deterministic heuristic over verifiable pipeline facts — NOT an LLM
 * self-assessment (7B models are notoriously uncalibrated and would happily report 0.95
 * on garbage). Components:
 * <ul>
 *   <li>+0.3 — classifier assigned a specific type (not GENERAL, not null)</li>
 *   <li>+0.2 — an identifier was extracted from the email</li>
 *   <li>+0.4 — the identifier was actually found in the backend (the draft is grounded in real data)</li>
 *   <li>+0.1 — a carrier rate matched (the draft quotes a real price)</li>
 * </ul>
 * The score is persisted on every drafting run regardless of the flag, so the distribution
 * accumulates for threshold tuning before anyone dares to switch auto-send on.
 *
 * <p>Three independent gates, all runtime-tunable on /settings:
 * <ol>
 *   <li>{@code feature.auto-send} — the only feature flag that defaults to OFF (it emails
 *       real customers without a human);</li>
 *   <li>{@code autosend.types} — TicketType allowlist, starts with STATUS_UPDATE only
 *       (factual answers grounded in TMS data; RFQ prices stay behind the operator);</li>
 *   <li>{@code autosend.threshold} — minimum confidence (default 0.8).</li>
 * </ol>
 *
 * <p>Double-send safety: {@link #autoSend} runs in its own transaction AFTER the pipeline
 * transaction has committed (see TicketProcessingService.process) — an optimistic-lock retry
 * of the pipeline can therefore never repeat an SMTP send. Inside autoSend the DRAFT_READY
 * status guard plus {@link SendRateLimiter} absorb event redeliveries, mirroring the manual
 * send path. An SMTP failure leaves the ticket DRAFT_READY — the operator fallback is always
 * a draft, never a lost email.
 */
@Service
public class AutoSendService {

    private static final Logger log = LoggerFactory.getLogger(AutoSendService.class);

    /** Feature flag; unlike other feature.* flags the code-level default is OFF. */
    public static final String FEATURE_KEY = "feature.auto-send";

    /** Minimum confidence for an automatic send (string setting, 0.0–1.0). */
    public static final String KEY_THRESHOLD = "autosend.threshold";

    /** Comma-separated {@link TicketType} allowlist for automatic sends. */
    public static final String KEY_TYPES = "autosend.types";

    static final double DEFAULT_THRESHOLD = 0.8;
    static final String DEFAULT_TYPES = "STATUS_UPDATE";

    private static final double WEIGHT_TYPE_SPECIFIC = 0.3;
    private static final double WEIGHT_IDENTIFIER_EXTRACTED = 0.2;
    private static final double WEIGHT_ITEM_FOUND = 0.4;
    private static final double WEIGHT_RATE_MATCHED = 0.1;

    private final EmailTicketRepository emailTicketRepository;
    private final EmailReplyService emailReplyService;
    private final SettingsService settingsService;
    private final SendRateLimiter sendRateLimiter;
    private final TicketAuditService auditService;
    private final QuoteFollowUpService quoteFollowUpService;
    private final Counter autoSentCounter;
    private final Counter autoFailedCounter;

    public AutoSendService(EmailTicketRepository emailTicketRepository,
                           EmailReplyService emailReplyService,
                           SettingsService settingsService,
                           SendRateLimiter sendRateLimiter,
                           TicketAuditService auditService,
                           QuoteFollowUpService quoteFollowUpService,
                           MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.emailReplyService = emailReplyService;
        this.settingsService = settingsService;
        this.sendRateLimiter = sendRateLimiter;
        this.auditService = auditService;
        this.quoteFollowUpService = quoteFollowUpService;
        this.autoSentCounter = Counter.builder("smartfreight_autosend_total")
                .description("Automatic reply send results")
                .tag("result", "sent")
                .register(meterRegistry);
        this.autoFailedCounter = Counter.builder("smartfreight_autosend_total")
                .description("Automatic reply send results")
                .tag("result", "failed")
                .register(meterRegistry);
    }

    /**
     * Heuristic confidence score for a generated draft. Pure function of pipeline facts —
     * deterministic and unit-testable, see class doc for the weights.
     *
     * @param type                classified business type (null = unclassified)
     * @param identifierExtracted whether step 1 extracted an identifier
     * @param itemFound           whether step 2 found the item in the backend
     * @param rateMatched         whether a carrier rate matched (Phase 1.2)
     * @return score in [0.0, 1.0], rounded to two decimals
     */
    public double confidenceFor(TicketType type, boolean identifierExtracted,
                                boolean itemFound, boolean rateMatched) {
        double score = 0.0;
        if (type != null && type != TicketType.GENERAL) {
            score += WEIGHT_TYPE_SPECIFIC;
        }
        if (identifierExtracted) {
            score += WEIGHT_IDENTIFIER_EXTRACTED;
        }
        if (itemFound) {
            score += WEIGHT_ITEM_FOUND;
        }
        if (rateMatched) {
            score += WEIGHT_RATE_MATCHED;
        }
        return Math.round(score * 100) / 100.0;
    }

    /**
     * Decides whether a ticket qualifies for auto-send: flag on, type in the allowlist,
     * confidence at or above the threshold. No side effects — safe to call both inside the
     * pipeline transaction (to skip the redundant Gmail draft) and again in {@link #autoSend}.
     *
     * @param ticket ticket with confidence and type already assigned
     * @return whether the draft should be sent without an operator
     */
    public boolean shouldAutoSend(EmailTicket ticket) {
        if (!settingsService.isFeatureEnabled(FEATURE_KEY, false)) {
            return false;
        }
        if (ticket.getTicketType() == null || !allowedTypes().contains(ticket.getTicketType())) {
            log.debug("Auto-send skipped for ticket {}: type {} not in allowlist",
                    ticket.getId(), ticket.getTicketType());
            return false;
        }
        double threshold = threshold();
        if (ticket.getAiConfidence() == null || ticket.getAiConfidence() < threshold) {
            log.info("Auto-send skipped for ticket {}: confidence {} below threshold {}",
                    ticket.getId(), ticket.getAiConfidence(), threshold);
            return false;
        }
        return true;
    }

    /**
     * Sends the draft and closes the ticket as AUTO_SENT, in its own transaction.
     *
     * Must be called outside the pipeline transaction (after it committed). Re-checks the
     * status and the gates because settings may have changed and RabbitMQ redelivery may
     * re-run the caller; a ticket that is no longer DRAFT_READY is a logged no-op.
     *
     * SMTP order mirrors the manual send path: send first, then flip the status — if the
     * commit fails after a successful send the ticket stays DRAFT_READY (operator sees a
     * draft for an already-answered email: annoying but safe, and loudly logged).
     *
     * @param ticketId ticket to auto-send
     */
    @Transactional
    public void autoSend(Long ticketId) {
        EmailTicket ticket = emailTicketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            log.warn("Auto-send: ticket {} not found", ticketId);
            return;
        }
        if (ticket.getStatus() != TicketStatus.DRAFT_READY) {
            log.info("Auto-send: ticket {} is {} — skipping (already handled)", ticketId, ticket.getStatus());
            return;
        }
        if (!shouldAutoSend(ticket)) {
            return;
        }
        if (!sendRateLimiter.tryAcquire(ticketId)) {
            log.warn("Auto-send rate-limited for ticket {} — another send within cooldown", ticketId);
            return;
        }

        try {
            emailReplyService.sendReply(ticket);
        } catch (Exception e) {
            autoFailedCounter.increment();
            log.error("Auto-send failed for ticket {} — left DRAFT_READY for the operator: {}",
                    ticketId, e.getMessage());
            auditService.record(ticketId, TicketAuditService.ACTION_AUTO_SEND,
                    "FAILED: " + e.getMessage());
            return;
        }

        ticket.setStatus(TicketStatus.AUTO_SENT);
        ticket.setUpdatedAt(LocalDateTime.now());
        emailTicketRepository.save(ticket);
        autoSentCounter.increment();
        auditService.record(ticketId, TicketAuditService.ACTION_AUTO_SEND,
                "to=" + ticket.getSenderEmail() + ", confidence=" + ticket.getAiConfidence());
        // Phase 2.5: a sent RFQ quote arms a one-shot follow-up. No-op for proactive tickets
        // (status updates, quote nudges) and non-RFQ types — guarded inside the service.
        quoteFollowUpService.armFromTicket(ticket);
        log.info("Ticket {} auto-sent to {} (confidence {})",
                ticketId, ticket.getSenderEmail(), ticket.getAiConfidence());
    }

    /** Threshold from settings, defaulting and clamping malformed values to a sane [0,1]. */
    double threshold() {
        String raw = settingsService.getString(KEY_THRESHOLD, String.valueOf(DEFAULT_THRESHOLD));
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Math.min(1.0, Math.max(0.0, parsed));
        } catch (NumberFormatException e) {
            log.warn("Malformed {} value '{}' — using default {}", KEY_THRESHOLD, raw, DEFAULT_THRESHOLD);
            return DEFAULT_THRESHOLD;
        }
    }

    /** Allowlisted types from settings; unknown names are ignored with a warning. */
    Set<TicketType> allowedTypes() {
        String raw = settingsService.getString(KEY_TYPES, DEFAULT_TYPES);
        EnumSet<TicketType> types = EnumSet.noneOf(TicketType.class);
        for (String name : raw.split(",")) {
            String trimmed = name.trim().toUpperCase();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                types.add(TicketType.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown ticket type '{}' in {} — ignored", trimmed, KEY_TYPES);
            }
        }
        return types;
    }
}
