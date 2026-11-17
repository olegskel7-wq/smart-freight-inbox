package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * SLA deadline calculator.
 *
 * The deadline always counts from when the email was RECEIVED (ticket createdAt), not
 * from when it was classified or picked up — the customer's clock starts at send time.
 * It is written in the existing persistence paths:
 *   1. ingest — GENERAL window (type unknown yet, no LLM on the IMAP thread);
 *   2. consumer step 0 — refined to the per-type window once the classifier assigns
 *      the type, or cleared when the email is IRRELEVANT (spam has no SLA).
 *
 * Breach detection: {@link com.smartfreight.inbox.scheduler.SlaBreachScheduler} flips
 * {@code slaBreached} on open tickets past their deadline once a minute, and every site that ends the
 * clock (send, auto-send, close) calls {@link #markBreachedIfOverdue} first — otherwise a ticket
 * answered late but before the next sweep would never be flagged.
 *
 * Gated by {@code feature.sla-timer} (plan-gating convention) — callers check
 * {@link #isEnabled()} before writing deadlines.
 */
@Service
public class SlaService {

    /** Feature flag gating SLA deadlines; off → no deadlines are written. */
    public static final String FEATURE_KEY = "feature.sla-timer";

    private final SettingsService settingsService;
    private final SlaProperties properties;
    /** The same meter SlaBreachScheduler increments (Micrometer returns the registered one). */
    private final Counter breachedCounter;

    public SlaService(SettingsService settingsService, SlaProperties properties, MeterRegistry meterRegistry) {
        this.settingsService = settingsService;
        this.properties = properties;
        this.breachedCounter = Counter.builder("smartfreight_sla_breached_total")
                .description("Open tickets that passed their SLA deadline without a reply")
                .register(meterRegistry);
    }

    /**
     * Flags the ticket as breached when its deadline has already passed and it is not flagged yet —
     * the scheduler's rule ({@code deadline < now}), applied at the moment the clock stops. Call it
     * before the status leaves the open set; the caller saves the ticket. No deadline (SLA off,
     * irrelevant mail) → nothing to flag.
     *
     * @return whether the flag was set now
     */
    public boolean markBreachedIfOverdue(EmailTicket ticket, LocalDateTime now) {
        if (ticket.isSlaBreached() || ticket.getSlaDeadlineAt() == null || !ticket.getSlaDeadlineAt().isBefore(now)) {
            return false;
        }
        ticket.setSlaBreached(true);
        breachedCounter.increment();
        return true;
    }

    /** Whether the SLA timer feature is on (default: enabled when the flag row is absent). */
    public boolean isEnabled() {
        return settingsService.isFeatureEnabled(FEATURE_KEY, true);
    }

    /**
     * Reply-by deadline for a ticket of the given type.
     *
     * @param type       ticket type; null (unclassified) is treated as GENERAL
     * @param receivedAt when the email was received (ticket createdAt) — the SLA clock anchor
     * @return deadline = receivedAt + the type's configured window
     */
    public LocalDateTime deadlineFor(TicketType type, LocalDateTime receivedAt) {
        return deadlineFor(type, false, receivedAt);
    }

    /**
     * Reply-by deadline for a ticket, tightened when the AI classifier escalated it.
     *
     * An escalated ticket (HIGH urgency or negative sentiment — see
     * {@link EmailClassificationService.ClassificationResult#isEscalated()}) gets the per-type window
     * scaled by {@code sla.escalation-factor} (default 0.5), so an urgent/angry email is due sooner.
     * The factor only ever shortens the window (min 1 minute); a normal ticket is unchanged.
     *
     * @param type       ticket type; null (unclassified) is treated as GENERAL
     * @param escalated  whether the classifier escalated this ticket (HIGH urgency / negative tone)
     * @param receivedAt when the email was received (ticket createdAt) — the SLA clock anchor
     * @return deadline = receivedAt + the (possibly escalated) window
     */
    public LocalDateTime deadlineFor(TicketType type, boolean escalated, LocalDateTime receivedAt) {
        TicketType effective = type != null ? type : TicketType.GENERAL;
        long minutes = properties.getMinutes()
                .getOrDefault(effective, properties.getDefaultMinutes());
        if (escalated) {
            minutes = Math.max(1, Math.round(minutes * properties.getEscalationFactor()));
        }
        return receivedAt.plusMinutes(minutes);
    }
}
