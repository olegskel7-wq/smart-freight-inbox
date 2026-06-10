package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.entity.OutboxEvent;
import com.smartfreight.inbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for OutboxTicketEventPublisher (Phase 1.3, transactional outbox — recording half).
 *
 * Verifies:
 * - the event row carries the right aggregate/type/payload
 * - calling outside an active transaction fails loudly (the atomicity guarantee
 *   would silently disappear otherwise)
 *
 * The "active transaction" flag is simulated via TransactionSynchronizationManager —
 * the same static state Spring's transaction interceptor sets.
 */
@ExtendWith(MockitoExtension.class)
class OutboxTicketEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @AfterEach
    void tearDown() {
        // Static thread-local state — always reset so other tests are unaffected.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void publishTicketCreated_insideTransaction_savesOutboxRow() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        OutboxTicketEventPublisher publisher =
                new OutboxTicketEventPublisher(outboxEventRepository, objectMapper);

        publisher.publishTicketCreated(42L);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("TICKET");
        assertThat(event.getAggregateId()).isEqualTo(42L);
        assertThat(event.getEventType()).isEqualTo(TicketEventMessage.TYPE_TICKET_CREATED);
        assertThat(event.getPayload()).contains("\"ticketId\":42");
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void publishRetryRequested_insideTransaction_usesRetryEventType() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        OutboxTicketEventPublisher publisher =
                new OutboxTicketEventPublisher(outboxEventRepository, objectMapper);

        publisher.publishRetryRequested(7L);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo(TicketEventMessage.TYPE_RETRY_REQUESTED);
    }

    @Test
    void publish_outsideTransaction_throwsIllegalState() {
        OutboxTicketEventPublisher publisher =
                new OutboxTicketEventPublisher(outboxEventRepository, objectMapper);

        assertThatThrownBy(() -> publisher.publishTicketCreated(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        verifyNoInteractions(outboxEventRepository);
    }
}
