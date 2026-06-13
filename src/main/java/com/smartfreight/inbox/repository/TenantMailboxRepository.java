package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.TenantMailbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to per-tenant mailbox rows (Phase 2.2 S3).
 *
 * <p>All queries are tenant-filtered by Hibernate {@code @TenantId} — except under the
 * system context, where the filter is off by design: {@code MailboxReconciler} reads
 * EVERY tenant's enabled mailboxes to maintain the adapter fleet.
 */
public interface TenantMailboxRepository extends JpaRepository<TenantMailbox, Long> {

    /** All adapters that should be running. System context → across all tenants. */
    List<TenantMailbox> findByEnabledTrue();

    /**
     * The current tenant's outbound sender: the first enabled mailbox that has SMTP
     * configured (deterministic oldest-first). Tenant-filtered by {@code @TenantId}, so
     * it must be called inside a tenant context. Empty → no per-tenant SMTP → the caller
     * falls back to the platform account.
     */
    Optional<TenantMailbox> findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc();
}
