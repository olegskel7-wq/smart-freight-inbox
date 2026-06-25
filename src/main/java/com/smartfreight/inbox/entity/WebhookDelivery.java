package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One queued delivery of a webhook event to a single {@link WebhookSubscription} (Phase 3.5).
 *
 * <p>Inserted in the SAME transaction as the business change that produced the event (the
 * transactional-outbox pattern, mirroring {@code outbox_event}): if the business write rolls back the
 * delivery vanishes with it; if it commits the delivery is durable and {@code WebhookDispatcher} will
 * attempt it. Delivery is at-least-once, so the {@link #eventId} doubles as an idempotency key for the
 * receiver. {@code payload} is the immutable signed envelope; the HMAC signature is recomputed per
 * attempt (it carries a fresh timestamp) and is therefore not stored.
 */
@Entity
@Table(name = "webhook_delivery")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters entity queries (off under the system-context dispatcher). */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private Long subscriptionId;

    /** Stable id of the logical event, shared by every delivery of it — the receiver dedupes on this. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** The JSON envelope sent as the request body — immutable once queued. */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** When the next attempt is due — a failed attempt pushes this into the future (backoff). */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    /** HTTP status of the last attempt (null if the request never got a response). */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** Short reason for the last failure, shown in the UI delivery log. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
}
