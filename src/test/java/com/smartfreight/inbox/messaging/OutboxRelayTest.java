package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.config.RabbitConfig;
import com.smartfreight.inbox.entity.OutboxEvent;
import com.smartfreight.inbox.repository.OutboxEventRepository;
import com.smartfreight.inbox.testutil.TestTransactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxRelay (Phase 1.3, transactional outbox — publishing half).
 *
 * Verifies:
 * - confirmed events are stamped published_at and counted
 * - a broker nack leaves the event unpublished (retried next tick)
 * - the loop stops at the first failure, preserving publication order
 * - an empty outbox touches neither the broker nor the counters
 *
 * The RabbitTemplate mock completes the CorrelationData future the way the real
 * broker confirm callback would, so the relay's blocking wait resolves instantly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private SimpleMeterRegistry meterRegistry;
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxRelay = new OutboxRelay(
                outboxEventRepository, rabbitTemplate, TestTransactions.noOpTemplate(), meterRegistry);
        ReflectionTestUtils.setField(outboxRelay, "batchSize", 50);
    }

    private OutboxEvent buildEvent(Long id, Long ticketId, String eventType) {
        return OutboxEvent.builder()
                .id(id)
                .tenantId(1L)
                .aggregateType("TICKET")
                .aggregateId(ticketId)
                .eventType(eventType)
                .payload("{\"ticketId\":" + ticketId + "}")
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** Makes the mocked template ack (or nack) every publish immediately. */
    private void stubBrokerConfirm(boolean ack) {
        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3, CorrelationData.class);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "test nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    /**
     * Two pending events, broker acks both — both stamped published_at,
     * routing key = event type, counter = 2.
     */
    @Test
    void publishPending_confirmedEvents_stampsPublishedAt() {
        OutboxEvent e1 = buildEvent(1L, 10L, TicketEventMessage.TYPE_TICKET_CREATED);
        OutboxEvent e2 = buildEvent(2L, 11L, TicketEventMessage.TYPE_RETRY_REQUESTED);
        when(outboxEventRepository.lockUnpublishedBatch(anyInt())).thenReturn(List.of(e1, e2));
        stubBrokerConfirm(true);

        outboxRelay.publishPending();

        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).isNotNull();

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.TICKET_EXCHANGE), eq(TicketEventMessage.TYPE_TICKET_CREATED),
                eq(e1.getPayload()), any(CorrelationData.class));
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.TICKET_EXCHANGE), eq(TicketEventMessage.TYPE_RETRY_REQUESTED),
                eq(e2.getPayload()), any(CorrelationData.class));

        assertThat(meterRegistry.counter("smartfreight_outbox_published_total").count())
                .isEqualTo(2.0);
    }

    /**
     * Broker nacks the publish — the event stays unpublished and the failure
     * counter is incremented; next tick will retry the same row.
     */
    @Test
    void publishPending_brokerNack_leavesEventUnpublished() {
        OutboxEvent event = buildEvent(1L, 10L, TicketEventMessage.TYPE_TICKET_CREATED);
        when(outboxEventRepository.lockUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        stubBrokerConfirm(false);

        outboxRelay.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(meterRegistry.counter("smartfreight_outbox_publish_failures_total").count())
                .isEqualTo(1.0);
    }

    /**
     * First event acked, second nacked — the loop stops: the third event is never
     * sent. Publication order (id order) is preserved across retries.
     */
    @Test
    void publishPending_failureMidBatch_stopsToPreserveOrder() {
        OutboxEvent ok = buildEvent(1L, 10L, TicketEventMessage.TYPE_TICKET_CREATED);
        OutboxEvent bad = buildEvent(2L, 11L, TicketEventMessage.TYPE_TICKET_CREATED);
        OutboxEvent untouched = buildEvent(3L, 12L, TicketEventMessage.TYPE_TICKET_CREATED);
        when(outboxEventRepository.lockUnpublishedBatch(anyInt()))
                .thenReturn(List.of(ok, bad, untouched));

        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3, CorrelationData.class);
            boolean ack = "1".equals(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "test nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        outboxRelay.publishPending();

        assertThat(ok.getPublishedAt()).isNotNull();
        assertThat(bad.getPublishedAt()).isNull();
        assertThat(untouched.getPublishedAt()).isNull();
        // Third event was never attempted: 2 sends total (ok + bad).
        verify(rabbitTemplate, times(2)).convertAndSend(
                anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    /**
     * Empty outbox — no broker interaction at all.
     */
    @Test
    void publishPending_noPendingEvents_doesNothing() {
        when(outboxEventRepository.lockUnpublishedBatch(anyInt())).thenReturn(List.of());

        outboxRelay.publishPending();

        verifyNoInteractions(rabbitTemplate);
    }
}
