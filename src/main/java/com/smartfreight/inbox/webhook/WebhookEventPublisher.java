package com.smartfreight.inbox.webhook;

import com.smartfreight.inbox.entity.WebhookDelivery;
import com.smartfreight.inbox.entity.WebhookSubscription;
import com.smartfreight.inbox.repository.WebhookDeliveryRepository;
import com.smartfreight.inbox.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records outbound webhook deliveries when a domain event fires (Phase 3.5).
 *
 * <p>"Publishing" is an INSERT into {@code webhook_delivery} within the CALLER's transaction — the same
 * transactional-outbox discipline as {@code OutboxTicketEventPublisher}: if the business change rolls
 * back the deliveries vanish with it; if it commits they are durable and {@link WebhookDispatcher}
 * sends them. The receiver being unreachable therefore never affects the business write.
 *
 * <p>One logical event fans out to one delivery per enabled, subscribed endpoint, all sharing one
 * {@code eventId} (the receiver's idempotency key) and one immutable envelope. Must be called inside a
 * tenant-scoped transaction — the subscription lookup is {@code @TenantId}-filtered to the event's tenant.
 */
@Component
public class WebhookEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);

    /** Bumped when the envelope shape changes incompatibly; sent as {@code api_version} in every payload. */
    private static final String API_VERSION = "2026-06-22";

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventPublisher(WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookDeliveryRepository deliveryRepository,
                                 ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Queues a delivery of {@code eventType} to every enabled subscription of {@code tenantId} that
     * wants it. No-op when the tenant has no matching subscription (the common case — a cheap indexed
     * query). Errors are logged and swallowed so a fault in the fan-out itself (payload serialization, the
     * subscription lookup) cannot propagate into the caller.
     *
     * <p><b>Limit of that guard:</b> the delivery rows are INSERTed in the caller's transaction (the
     * transactional-outbox discipline — atomic with the business change), and {@code IDENTITY} ids force an
     * immediate flush. A DB-level failure on that INSERT therefore aborts the transaction, so the business
     * change rolls back with it — the queue is <i>atomic</i> with the business write, not <i>isolated</i>
     * from it (the same contract as the {@code outbox_event} it mirrors). True isolation would require a
     * SAVEPOINT around the insert; for an auxiliary webhook the atomic-outbox behaviour is acceptable.
     *
     * @param eventType one of {@link WebhookEventType}
     * @param tenantId  the event's tenant (the current transaction's tenant)
     * @param data      the event-specific body placed under the envelope's {@code data} field
     */
    public void publish(String eventType, Long tenantId, Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // The outbox guarantee only holds inside the business transaction; a call outside one is a bug.
            throw new IllegalStateException(
                    "WebhookEventPublisher must be called inside an active transaction");
        }
        try {
            List<WebhookSubscription> subscriptions = subscriptionRepository.findByEnabledTrue().stream()
                    .filter(s -> s.subscribesTo(eventType))
                    .toList();
            if (subscriptions.isEmpty()) {
                return;
            }

            UUID eventId = UUID.randomUUID();
            String payload = buildEnvelope(eventId, eventType, tenantId, data);

            for (WebhookSubscription subscription : subscriptions) {
                WebhookDelivery delivery = new WebhookDelivery();
                delivery.setTenantId(tenantId);
                delivery.setSubscriptionId(subscription.getId());
                delivery.setEventId(eventId);
                delivery.setEventType(eventType);
                delivery.setPayload(payload);
                delivery.setNextAttemptAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
            }
            log.debug("Queued {} webhook delivery(ies) for event {} (tenant {})",
                    subscriptions.size(), eventType, tenantId);
        } catch (Exception e) {
            log.warn("Failed to queue webhook deliveries for event {} (tenant {}): {}",
                    eventType, tenantId, e.getMessage());
        }
    }

    /**
     * Queues a single delivery to one specific subscription, bypassing its event-type filter. Used for
     * the "send test event" action so the operator can verify an endpoint + signature regardless of what
     * the subscription is filtered to. Must run inside the subscription's tenant transaction.
     */
    public void publishTo(WebhookSubscription subscription, String eventType, Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "WebhookEventPublisher must be called inside an active transaction");
        }
        UUID eventId = UUID.randomUUID();
        String payload = buildEnvelope(eventId, eventType, subscription.getTenantId(), data);
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTenantId(subscription.getTenantId());
        delivery.setSubscriptionId(subscription.getId());
        delivery.setEventId(eventId);
        delivery.setEventType(eventType);
        delivery.setPayload(payload);
        delivery.setNextAttemptAt(LocalDateTime.now());
        deliveryRepository.save(delivery);
    }

    /** Serializes the stable public envelope: {@code id/type/occurred_at/api_version/tenant_id/data}. */
    private String buildEnvelope(UUID eventId, String eventType, Long tenantId, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", eventType);
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("api_version", API_VERSION);
        envelope.put("tenant_id", tenantId);
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
