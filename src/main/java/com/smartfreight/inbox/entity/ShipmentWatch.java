package com.smartfreight.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A proactive status-update subscription (Phase 2.4).
 *
 * <p>The product is reactive by default — a customer emails, the AI replies. A watch turns
 * that single enquiry into an ongoing subscription: once a customer asks about a shipment
 * (a STATUS_UPDATE ticket carrying an identifier), {@code StatusWatchScheduler} polls the
 * backend and emails the customer whenever the shipment reaches a milestone status, until it
 * is DELIVERED (at which point the watch is deactivated).
 *
 * <p>{@code lastNotifiedStatus} is the status the customer was last told about — seeded from
 * the reactive reply when the watch is created, then advanced by the poller — so the same
 * status is never notified twice. Guarded by both isolation layers: {@code @TenantId} for
 * entity queries and the V22 RLS policy for everything else.
 */
@Entity
@Table(name = "shipment_watch")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentWatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant — assigned on insert, filters every entity query. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Tracked-item identifier the customer asked about (e.g. an ISO 6346 container number). */
    @Column(name = "identifier", nullable = false)
    private String identifier;

    /** Where proactive updates are sent — the address that originally enquired. */
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    /**
     * The status the customer was last notified of (raw backend status name, e.g. "RELEASED").
     * Null until the first notification; compared against the polled status so a milestone is
     * never re-sent.
     */
    @Column(name = "last_notified_status", length = 50)
    private String lastNotifiedStatus;

    /** False once the shipment is DELIVERED — the watch is done and the poller skips it. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
