package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.config.RabbitConfig;
import com.smartfreight.inbox.service.TicketProcessingService;
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

    public TicketEventConsumer(TicketProcessingService ticketProcessingService,
                               ObjectMapper objectMapper) {
        this.ticketProcessingService = ticketProcessingService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.PROCESS_QUEUE, containerFactory = "aiPipelineListenerFactory")
    public void onTicketEvent(String payload) {
        // Jackson 3 throws unchecked JacksonException on malformed payload → retry advice → DLQ
        TicketEventMessage message = objectMapper.readValue(payload, TicketEventMessage.class);
        log.info("Consuming {} for ticket {}", message.eventType(), message.ticketId());
        ticketProcessingService.process(message.ticketId());
    }
}
