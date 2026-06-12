package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.config.RabbitConfig;
import com.smartfreight.inbox.service.TicketProcessingService;
import com.smartfreight.inbox.tenancy.TenancyProperties;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ consumer that runs the AI pipeline for ticket events (Phase 1.3).
 *
 * Replaces the old in-JVM hand-off (TaskExecutor in EmailIngestionService /
 * RetryScheduler). Concurrency and backpressure live in the listener factory
 * ({@link RabbitConfig#aiPipelineListenerFactory}): 2 consumers, prefetch=1.
 *
 * Idempotency: delivery is at-least-once (outbox relay may re-publish, the broker
 * may redeliver). That is safe because {@code TicketProcessingService.processAttempt}
 * only acts on tickets in RECEIVED / NEW / RETRY_LATER — a duplicate event for an
 * already processed ticket is a logged no-op.
 *
 * Poison messages: an unparseable payload throws, the retry advice gives up after
 * 3 attempts and the message lands in the DLQ ({@link RabbitConfig#PROCESS_DLQ}).
 * Business failures do NOT throw — process() converts them to FAILED/RETRY_LATER
 * ticket states, which keeps "broken message" and "broken ticket" cleanly separated.
 */
@Component
public class TicketEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketEventConsumer.class);

    private final TicketProcessingService ticketProcessingService;
    private final ObjectMapper objectMapper;
    private final TenancyProperties tenancyProperties;

    public TicketEventConsumer(TicketProcessingService ticketProcessingService,
                               ObjectMapper objectMapper,
                               TenancyProperties tenancyProperties) {
        this.ticketProcessingService = ticketProcessingService;
        this.objectMapper = objectMapper;
        this.tenancyProperties = tenancyProperties;
    }

    @RabbitListener(queues = RabbitConfig.PROCESS_QUEUE, containerFactory = "aiPipelineListenerFactory")
    public void onTicketEvent(String payload) {
        // Jackson 3 throws unchecked JacksonException on malformed payload → retry advice → DLQ
        TicketEventMessage message = objectMapper.readValue(payload, TicketEventMessage.class);
        log.info("Consuming {} for ticket {} (tenant {})",
                message.eventType(), message.ticketId(), message.tenantId());

        // Phase 2.2: the whole pipeline run is bound to the event's tenant — every ticket
        // reload, audit row and settings read below is tenant-scoped. The context must be
        // set HERE, before process() opens its transaction: Hibernate fixes the session's
        // tenant at session open. Pre-2.2 in-flight messages carry no tenantId → default.
        Long tenantId = message.tenantId() != null ? message.tenantId() : tenancyProperties.getDefaultId();
        TenantContext.runAs(tenantId, () -> ticketProcessingService.process(message.ticketId()));
    }
}
