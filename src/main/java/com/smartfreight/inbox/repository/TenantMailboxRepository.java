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
     * A ticket's own mailbox as its outbound sender — only if it is enabled and has SMTP
     * configured. A derived query (not {@code findById}) on purpose: it goes through the
     * {@code @TenantId} filter as well as RLS, so another tenant's mailbox id resolves to
     * empty, never to that tenant's account.
     */
    Optional<TenantMailbox> findByIdAndEnabledTrueAndSmtpHostIsNotNull(Long id);

    /**
     * The current tenant's default outbound sender: the first enabled mailbox that has SMTP
     * configured (deterministic oldest-first) — used when a ticket's own mailbox is unknown
     * or unusable. Tenant-filtered by {@code @TenantId}, so it must be called inside a tenant
     * context. Empty → no per-tenant SMTP → the caller falls back to the platform account.
     */
    Optional<TenantMailbox> findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc();

    /** The current tenant's mailbox for an account address — demo harness upsert key. */
    Optional<TenantMailbox> findFirstByUsernameOrderByIdAsc(String username);
}
