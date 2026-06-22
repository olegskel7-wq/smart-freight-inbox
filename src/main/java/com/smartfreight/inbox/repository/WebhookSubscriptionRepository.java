package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Access to webhook subscriptions (Phase 3.5).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId}: {@link #findByEnabledTrue()} and the listing
 * methods return only the current tenant's rows. The dispatcher loads a subscription by id under the
 * system context (filter off) to fetch the URL + secret for a queued delivery.
 */
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    /** Enabled subscriptions for the current tenant — the publisher fans an event out to these. */
    List<WebhookSubscription> findByEnabledTrue();

    /** All of the current tenant's subscriptions, newest first, for the management UI. */
    List<WebhookSubscription> findAllByOrderByIdDesc();
}
