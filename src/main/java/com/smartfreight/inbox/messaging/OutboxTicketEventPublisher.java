package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.entity.OutboxEvent;
import com.smartfreight.inbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * {@link TicketEventPublisher} implementation backed by the transactional outbox.
 *
 * "Publishing" here is just an INSERT into {@code outbox_event} within the caller's
 * transaction — if the business change rolls back, the event vanishes with it; if it
 * commits, the event is durable and {@link OutboxRelay} will deliver it to RabbitMQ.
 * The broker being down therefore never fails ingestion: events simply queue up in
 * the outbox until the relay can publish them (graceful degradation for free).
 */
@Component
public class OutboxTicketEventPublisher implements TicketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxTicketEventPublisher.class);

    private static final String AGGREGATE_TICKET = "TICKET";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxTicketEventPublisher(OutboxEventRepository outboxEventRepository,
                                      ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishTicketCreated(Long ticketId) {
        record(ticketId, TicketEventMessage.TYPE_TICKET_CREATED);
    }

    @Override
    public void publishRetryRequested(Long ticketId) {
        record(ticketId, TicketEventMessage.TYPE_RETRY_REQUESTED);
    }

    private void record(Long ticketId, String eventType) {
        // The whole point of the outbox is atomicity with the business change —
        // outside a transaction that guarantee silently disappears, so fail loudly.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TicketEventPublisher must be called inside an active transaction");
        }

        TicketEventMessage message = new TicketEventMessage(ticketId, eventType, Instant.now());
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TICKET)
                .aggregateId(ticketId)
                .eventType(eventType)
                // Jackson 3: java.time works out of the box, exceptions are unchecked
                .payload(objectMapper.writeValueAsString(message))
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);
        log.debug("Outbox event recorded: type={}, ticketId={}", eventType, ticketId);
    }
}
