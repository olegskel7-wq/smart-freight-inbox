package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.repository.OutboxEventRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outbox health gauges (Phase 1.3, session 2).
 *
 * Complements the relay's counters (published / publish-failures): counters show
 * throughput, these gauges show the BACKLOG — the thing an operator actually
 * watches when RabbitMQ is down and events are accumulating in PostgreSQL.
 *
 * - smartfreight_outbox_pending: events not yet relayed to the broker.
 * - smartfreight_outbox_oldest_pending_age_seconds: how long the head of the
 *   backlog has been waiting (0 when drained). Distinguishes "busy but flowing"
 *   from "stuck": pending=5 with age=3s is normal load, pending=5 with age=900s
 *   means the relay has been failing for 15 minutes.
 *
 * Gauges are sampled on each Prometheus scrape (every 15s) — both queries are
 * O(pending) thanks to the partial index on unpublished rows.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
        // Phase 2.2: gauges sample on the Prometheus scrape thread, which has no tenant —
        // SYSTEM state, because ops metrics must see the whole backlog, not one tenant's.
        Gauge.builder("smartfreight_outbox_pending", outboxEventRepository,
                        repo -> TenantContext.callAsSystem(repo::countByPublishedAtIsNull))
                .description("Outbox events not yet published to RabbitMQ")
                .register(meterRegistry);

        Gauge.builder("smartfreight_outbox_oldest_pending_age_seconds", outboxEventRepository,
                        repo -> TenantContext.callAsSystem(() -> oldestPendingAgeSeconds(repo)))
                .description("Age in seconds of the oldest unpublished outbox event (0 = drained)")
                .register(meterRegistry);
    }

    private static double oldestPendingAgeSeconds(OutboxEventRepository repo) {
        LocalDateTime oldest = repo.findOldestUnpublishedCreatedAt();
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest, LocalDateTime.now()).toMillis() / 1000.0);
    }
}
