package com.smartfreight.inbox.webhook;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookEventPublisher} (Phase 3.5): the fan-out of one domain event to one
 * delivery per matching subscription, the event-type filter, and the in-transaction guard. The "active
 * transaction" flag is simulated via {@link TransactionSynchronizationManager}, as in the outbox tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookEventPublisherTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private WebhookEventPublisher publisher() {
        return new WebhookEventPublisher(subscriptionRepository, deliveryRepository, objectMapper);
    }

    private WebhookSubscription sub(long id, String eventTypes) {
        WebhookSubscription s = new WebhookSubscription();
        s.setId(id);
        s.setTenantId(1L);
        s.setEventTypes(eventTypes);
        s.setEnabled(true);
        return s;
    }

    @Test
    void fansOutToEveryMatchingSubscriptionWithSharedEnvelope() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(subscriptionRepository.findByEnabledTrue())
                .thenReturn(List.of(sub(1, "*"), sub(2, "ticket.created")));

        publisher().publish(WebhookEventType.TICKET_CREATED, 1L, Map.of("ticket_id", 9));

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        List<WebhookDelivery> saved = captor.getAllValues();
        // Same logical event id + payload across both deliveries (the receiver's idempotency key).
        assertThat(saved.get(0).getEventId()).isEqualTo(saved.get(1).getEventId());
        assertThat(saved.get(0).getPayload()).contains("\"type\":\"ticket.created\"").contains("\"ticket_id\":9");
        assertThat(saved).allSatisfy(d -> assertThat(d.getTenantId()).isEqualTo(1L));
    }

    @Test
    void skipsSubscriptionsNotSubscribedToTheType() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(subscriptionRepository.findByEnabledTrue())
                .thenReturn(List.of(sub(1, "some.other.event")));

        publisher().publish(WebhookEventType.TICKET_CREATED, 1L, Map.of());

        verify(deliveryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noSubscriptionsIsANoOp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(subscriptionRepository.findByEnabledTrue()).thenReturn(List.of());

        publisher().publish(WebhookEventType.TICKET_CREATED, 1L, Map.of());

        verify(deliveryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void outsideTransactionThrows() {
        assertThatThrownBy(() -> publisher().publish(WebhookEventType.TICKET_CREATED, 1L, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }
}
