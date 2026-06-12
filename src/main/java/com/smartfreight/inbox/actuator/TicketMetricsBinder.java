package com.smartfreight.inbox.actuator;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Registers Gauge metrics for each ticket status.
 * The Gauge periodically queries the database via EmailTicketRepository,
 * so Grafana always sees the live ticket count per status.
 */
@Component
public class TicketMetricsBinder implements MeterBinder {

    private final EmailTicketRepository emailTicketRepository;

    public TicketMetricsBinder(EmailTicketRepository emailTicketRepository) {
        this.emailTicketRepository = emailTicketRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (TicketStatus status : TicketStatus.values()) {
            // Phase 2.2: sampled on the scrape thread (no tenant) — SYSTEM state so the
            // ops dashboard counts tickets across all tenants.
            Gauge.builder("smartfreight_tickets_current_count",
                          emailTicketRepository,
                          repo -> TenantContext.callAsSystem(() -> repo.countByStatus(status)))
                 .description("Current number of tickets by status")
                 .tag("status", status.name())
                 .register(registry);
        }
    }
}
