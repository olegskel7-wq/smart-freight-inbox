package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.config.RabbitConfig;
import com.smartfreight.inbox.entity.OutboxEvent;
import com.smartfreight.inbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Second half of the transactional outbox (Phase 1.3): polls {@code outbox_event}
 * for unpublished rows and relays them to RabbitMQ.
 *
 * Reliability model:
 * - Rows are locked with FOR UPDATE SKIP LOCKED (multi-instance safe).
 * - Each publish waits for a broker confirm; only then is the row stamped published_at.
 * - On any failure the loop stops: rows already confirmed in this batch keep their
 *   stamp (the transaction commits), the failed row stays pending and is retried on
 *   the next tick. A crash between confirm and commit re-publishes the event —
 *   at-least-once, which the idempotent consumer absorbs.
 * - Broker down → publish fails → events accumulate in the outbox; ingestion is
 *   never blocked. When the broker returns, the backlog drains in id order.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** How long to wait for a single publisher confirm before treating it as a failure. */
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final Counter publishedCounter;
    private final Counter publishFailedCounter;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       RabbitTemplate rabbitTemplate,
                       MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.publishedCounter = Counter.builder("smartfreight_outbox_published_total")
                .description("Outbox events successfully published to RabbitMQ")
                .register(meterRegistry);
        this.publishFailedCounter = Counter.builder("smartfreight_outbox_publish_failures_total")
                .description("Outbox publish attempts that failed or were nacked")
                .register(meterRegistry);
    }

    /**
     * Polls and publishes pending outbox events.
     *
     * fixedDelay (not fixedRate): the next poll starts only after the previous one
     * finished, so a slow broker cannot pile up overlapping relay runs.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}", initialDelay = 10_000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("OutboxRelay: publishing {} pending event(s)", batch.size());

        for (OutboxEvent event : batch) {
            try {
                publishWithConfirm(event);
                event.setPublishedAt(LocalDateTime.now());
                publishedCounter.increment();
            } catch (Exception e) {
                publishFailedCounter.increment();
                // Stop at the first failure to preserve publication order (id order).
                // This row's lock is released on commit; next tick retries it.
                log.warn("OutboxRelay: publish failed for event id={} ({}); will retry next tick",
                        event.getId(), e.getMessage());
                break;
            }
        }
    }

    /**
     * Publishes one event and blocks until the broker confirms it.
     *
     * The event type doubles as the routing key ("ticket.created" etc.), so new event
     * types route without touching this class. Correlation id = outbox row id, which
     * makes broker-side tracing trivial.
     */
    private void publishWithConfirm(OutboxEvent event) throws Exception {
        CorrelationData correlation = new CorrelationData(String.valueOf(event.getId()));
        rabbitTemplate.convertAndSend(
                RabbitConfig.TICKET_EXCHANGE, event.getEventType(), event.getPayload(), correlation);
        CorrelationData.Confirm confirm =
                correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (confirm == null || !confirm.isAck()) {
            throw new IllegalStateException("Broker nacked outbox event id=" + event.getId()
                    + (confirm != null ? ": " + confirm.getReason() : ""));
        }
    }
}
