package com.smartfreight.inbox.messaging;

import org.springframework.stereotype.Component;

/**
 * Bridges the Spring-managed {@link TicketLiveEventService} to the JPA {@link TicketLiveEventListener},
 * which Hibernate instantiates outside the Spring container (Phase 3.2).
 *
 * <p>JPA entity listeners are not Spring beans, so they cannot use constructor injection portably.
 * Rather than rely on Hibernate's optional bean-container integration, this {@code @Component} captures
 * the service into a {@code static} reference at startup; the listener reads it from there. The
 * reference is {@code volatile} for safe publication, and the listener no-ops while it is null — so a
 * context that never wired the service (e.g. a future test slice) degrades silently instead of failing
 * a ticket write.
 */
@Component
public class TicketLiveEventBridge {

    private static volatile TicketLiveEventService service;

    public TicketLiveEventBridge(TicketLiveEventService service) {
        TicketLiveEventBridge.service = service;
    }

    /** The wired live-event service, or {@code null} before context startup / in a context without it. */
    static TicketLiveEventService service() {
        return service;
    }
}
