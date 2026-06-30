package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.enums.TicketType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * SLA windows per ticket type (Phase 1.5), bound from the {@code sla.*} config block.
 *
 * Relaxed binding maps yaml keys like {@code booking-confirm} to {@link TicketType}
 * names. A type missing from the map falls back to {@link #defaultMinutes} — that is
 * also what untyped tickets (null type → GENERAL) get unless GENERAL is overridden.
 */
@Component
@ConfigurationProperties(prefix = "sla")
@Getter
@Setter
public class SlaProperties {

    /** Reply window in minutes per ticket type. */
    private Map<TicketType, Long> minutes = new EnumMap<>(TicketType.class);

    /** Fallback window for types not listed under {@code sla.minutes}. */
    private long defaultMinutes = 480;

    /**
     * Phase 3.10: multiplier applied to the per-type window when a ticket is escalated (HIGH urgency
     * or negative sentiment). 0.5 = half the time to reply for an urgent/angry email. Must be in (0,1]
     * to tighten (never relax) the deadline; 1.0 disables the bump.
     */
    private double escalationFactor = 0.5;
}
