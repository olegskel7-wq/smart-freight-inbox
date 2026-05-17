package com.smartfreight.inbox.actuator;

import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
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
            Gauge.builder("smartfreight_tickets_current_count", 
                          emailTicketRepository, 
                          repo -> repo.countByStatus(status))
                 .description("Current number of tickets by status")
                 .tag("status", status.name())
                 .register(registry);
        }
    }
}
