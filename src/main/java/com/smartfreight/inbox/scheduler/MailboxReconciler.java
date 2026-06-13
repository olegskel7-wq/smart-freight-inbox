package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps the IMAP adapter fleet in sync with the {@code tenant_mailbox} table (Phase 2.2 S3).
 *
 * <p>Every pass reads ALL tenants' enabled mailboxes (system context — this is exactly the
 * cross-tenant scheduler case the SYSTEM state exists for) and hands the list to
 * {@link MailboxImapAdapterManager#reconcile}: new rows get adapters, edited rows get
 * rebuilt ones, disabled/deleted rows get theirs stopped. A mailbox saved on
 * {@code /settings} therefore starts ingesting within one interval, no restart.
 *
 * <p>Deliberately NOT ShedLock-guarded, unlike the other schedulers: the adapter fleet is
 * per-instance local state, so every instance must run its own reconcile. Under a future
 * multi-instance deployment each instance would watch every mailbox and the per-tenant
 * message-id dedup would absorb the duplicates — wasteful but correct; partitioning
 * mailboxes across instances is a scale problem for later.
 */
@Component
public class MailboxReconciler {

    private final TenantMailboxRepository mailboxes;
    private final MailboxImapAdapterManager adapterManager;

    public MailboxReconciler(TenantMailboxRepository mailboxes,
                             MailboxImapAdapterManager adapterManager) {
        this.mailboxes = mailboxes;
        this.adapterManager = adapterManager;
    }

    /**
     * Default 30 s: a saved mailbox should start ingesting while the operator is still on
     * the settings page. The pass is one indexed SELECT + an in-memory diff — cheap enough
     * that latency, not load, picks the interval. First run doubles as startup wiring
     * (initial delay 5 s gives MailboxBootstrap time to import the env mailbox).
     */
    @Scheduled(fixedDelayString = "${mailbox.reconcile-interval-ms:30000}", initialDelay = 5_000)
    public void reconcile() {
        List<TenantMailbox> desired = TenantContext.callAsSystem(mailboxes::findByEnabledTrue);
        adapterManager.reconcile(desired);
    }
}
