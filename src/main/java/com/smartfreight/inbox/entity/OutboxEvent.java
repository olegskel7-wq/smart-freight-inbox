package com.smartfreight.inbox.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code outbox_event} table (Phase 1.3, transactional outbox).
 *
 * One row = one domain event awaiting publication to RabbitMQ. The row is inserted
 * in the SAME transaction as the business change it describes (e.g. a new ticket),
 * which makes "ticket saved" and "event recorded" atomic — the classic dual-write
 * problem (DB committed but broker publish lost, or vice versa) cannot happen.
 *
 * {@link com.smartfreight.inbox.messaging.OutboxRelay} polls unpublished rows,
 * publishes them to the broker with publisher confirms, and stamps {@code publishedAt}.
 * Delivery is therefore at-least-once; consumers must be idempotent.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /** Primary key. Also defines publication order (the relay publishes in id order). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning tenant (Phase 2.2). Deliberately NOT {@code @TenantId}: events are also
     * recorded from the system context (RetryScheduler re-enqueueing tickets of many
     * tenants in one run), where Hibernate has no tenant to auto-assign. The publisher
     * sets it explicitly; RLS still guards the table.
     */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Aggregate kind the event belongs to, e.g. "TICKET". */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** Identifier of the aggregate (e.g. ticket id). */
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /**
     * Event type, doubles as the RabbitMQ routing key
     * (e.g. "ticket.created", "ticket.retry-requested").
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Serialized event body (JSON), written at business-transaction time. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Null while the event is pending; set by the relay after a broker confirm. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
