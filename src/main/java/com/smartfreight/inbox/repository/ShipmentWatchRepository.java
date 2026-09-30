package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.ShipmentWatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to proactive status-update subscriptions (Phase 2.4).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId} — except under the system context, where
 * the filter is off by design: {@code StatusWatchScheduler} reads EVERY tenant's active
 * watches in one pass, then processes each inside its owning tenant's scope.
 */
public interface ShipmentWatchRepository extends JpaRepository<ShipmentWatch, Long> {

    /** All live subscriptions. System context → across all tenants (the poller's first phase). */
    List<ShipmentWatch> findByActiveTrue();

    /** Upsert lookup within the current tenant: a customer re-asking updates their existing watch. */
    Optional<ShipmentWatch> findByIdentifierAndCustomerEmail(String identifier, String customerEmail);

    /** Whether any watch (active or not) replies through this mailbox — guards renaming it (Gate B15). */
    boolean existsByMailboxId(Long mailboxId);
}
