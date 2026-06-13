package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.QuoteFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to proactive quote-follow-up subscriptions (Phase 2.5).
 *
 * <p>Tenant-filtered by Hibernate {@code @TenantId} — except under the system context, where the
 * filter is off by design: {@code QuoteFollowUpScheduler} reads EVERY tenant's active follow-ups
 * in one pass, then processes each inside its owning tenant's scope.
 */
public interface QuoteFollowUpRepository extends JpaRepository<QuoteFollowUp, Long> {

    /** All live follow-ups. System context → across all tenants (the poller's first phase). */
    List<QuoteFollowUp> findByActiveTrue();

    /** Upsert lookup within the current tenant: re-sending a quote refreshes its existing follow-up. */
    Optional<QuoteFollowUp> findByTicketId(Long ticketId);
}
