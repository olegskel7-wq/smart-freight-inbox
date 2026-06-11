package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * SLA deadline calculator (Phase 1.5).
 *
 * The deadline always counts from when the email was RECEIVED (ticket createdAt), not
 * from when it was classified or picked up — the customer's clock starts at send time.
 * It is written in the existing persistence paths:
 *   1. ingest — GENERAL window (type unknown yet, no LLM on the IMAP thread);
 *   2. consumer step 0 — refined to the per-type window once the classifier assigns
 *      the type, or cleared when the email is IRRELEVANT (spam has no SLA).
 *
 * Breach detection is a separate concern: {@link com.smartfreight.inbox.scheduler.SlaBreachScheduler}
 * flips {@code slaBreached} on open tickets past their deadline.
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

    public SlaService(SettingsService settingsService, SlaProperties properties) {
        this.settingsService = settingsService;
        this.properties = properties;
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
        TicketType effective = type != null ? type : TicketType.GENERAL;
        long minutes = properties.getMinutes()
                .getOrDefault(effective, properties.getDefaultMinutes());
        return receivedAt.plusMinutes(minutes);
    }
}
