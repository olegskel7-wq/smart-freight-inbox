package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Access to queued webhook deliveries (Phase 3.5).
 *
 * <p>The dispatcher's poll ({@link #lockDueBatch}) runs under the system context, cross-tenant in one
 * pass — exactly like {@code OutboxEventRepository.lockUnpublishedBatch}. The UI log methods run under
 * a tenant context and are filtered by {@code @TenantId} / RLS.
 */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * Locks and returns the next batch of due {@code PENDING} deliveries (any tenant) in id order.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} makes the poll safe across multiple instances: each grabs a
     * disjoint set instead of blocking on or double-sending rows another instance holds. Must run
     * inside a transaction — the row locks live until commit.
     *
     * @param now        cutoff; only rows whose next attempt is due are returned
     * @param batchSize  maximum rows to lock
     */
    @Query(value = """
            SELECT * FROM webhook_delivery
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> lockDueBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /** Recent deliveries for one subscription (current tenant), newest first, for the UI log. */
    List<WebhookDelivery> findBySubscriptionIdOrderByIdDesc(Long subscriptionId, Pageable pageable);

    /** Number of deliveries still waiting to be sent — exposed as a Prometheus gauge of backlog health. */
    long countByStatus(com.smartfreight.inbox.entity.WebhookDeliveryStatus status);
}
