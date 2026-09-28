package com.smartfreight.inbox.startup;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Wires the demo tenants to the fake mail servers (Phase 4.8 S2 — demo mail harness).
 *
 * <p>Demo mailboxes cannot be seeded by SQL: {@code password_enc} is AES-GCM under the
 * deployment's own {@code MAILBOX_ENCRYPTION_KEY}. So they are upserted here, at startup,
 * through {@link SecretCipher} — the same path a mailbox saved on {@code /settings} takes.
 * The reconciler then starts their IMAP adapters within one interval, and every demo email
 * runs the real pipeline: IMAP IDLE → ingestion → outbox → classification → draft → reply.
 *
 * <p>Rows are keyed by (tenant, demo address), never by host, so switching between an IDE
 * run ({@code localhost}) and the compose stack ({@code greenmail}) just rewrites the host.
 *
 * <p>Harness off → the demo rows are DELETED (they are generated config, rebuilt on the next
 * demo start; nothing references {@code tenant_mailbox}). Merely disabling them is not enough:
 * a lingering row still counts as "this tenant has a mailbox", so {@link MailboxBootstrap}
 * would never re-import the env mailbox again. For the same reason this runner is ordered
 * AFTER {@link MailboxBootstrap}.
 *
 * <p>Demo-only code runs on every start, production included, so a failure is logged per
 * inbox and never aborts startup — same rule as the adapter fleet (one broken mailbox never
 * blocks the others).
 */
@Component
@Order(MailboxBootstrap.ORDER + 1)
public class DemoMailboxBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoMailboxBootstrap.class);

    private final DemoMailProperties properties;
    private final TenantMailboxRepository mailboxes;
    private final TenantRepository tenants;
    private final SecretCipher secretCipher;

    public DemoMailboxBootstrap(DemoMailProperties properties,
                                TenantMailboxRepository mailboxes,
                                TenantRepository tenants,
                                SecretCipher secretCipher) {
        this.properties = properties;
        this.mailboxes = mailboxes;
        this.tenants = tenants;
        this.secretCipher = secretCipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isEnabled()) {
            setUp();
        } else {
            tearDown();
        }
    }

    private void setUp() {
        if (!secretCipher.isConfigured()) {
            log.warn("DEMO_MAIL_ENABLED is set but MAILBOX_ENCRYPTION_KEY is not — demo mailboxes "
                    + "cannot be stored. Set the key; no demo adapter will run until then.");
            return;
        }
        properties.getInboxes().forEach((tenantId, address) -> {
            try {
                if (!TenantContext.callAsSystem(() -> tenants.existsById(tenantId))) {
                    log.warn("Demo mailbox {} skipped: tenant {} does not exist (seed it with the "
                            + "dev-seed V31 demo dataset first)", address, tenantId);
                    return;
                }
                TenantContext.runAs(tenantId, () -> upsert(tenantId, address));
            } catch (RuntimeException e) {
                log.error("Demo mailbox {} (tenant {}) could not be set up", address, tenantId, e);
            }
        });
    }

    /** No tenant-existence check: under RLS the lookup for a missing tenant is simply empty. */
    private void tearDown() {
        properties.getInboxes().forEach((tenantId, address) -> {
            try {
                TenantContext.runAs(tenantId, () -> mailboxes.findFirstByUsernameOrderByIdAsc(address)
                        .ifPresent(mailbox -> {
                            mailboxes.delete(mailbox);
                            log.info("Demo mail harness is off — removed demo mailbox {} (tenant {})",
                                    address, tenantId);
                        }));
            } catch (RuntimeException e) {
                log.error("Demo mailbox {} (tenant {}) could not be removed", address, tenantId, e);
            }
        });
    }

    private void upsert(Long tenantId, String address) {
        TenantMailbox mailbox = mailboxes.findFirstByUsernameOrderByIdAsc(address)
                .orElseGet(TenantMailbox::new);
        boolean created = mailbox.getId() == null;
        mailbox.setHost(properties.getImapHost());
        mailbox.setPort(properties.getImapPort());
        mailbox.setUsername(address);
        mailbox.setSmtpHost(properties.getSmtpHost());
        mailbox.setSmtpPort(properties.getSmtpPort());
        mailbox.setFromAddress(address);
        // Always re-encrypt: this runs once per start, before any adapter exists, and it also
        // heals a row left under a since-rotated MAILBOX_ENCRYPTION_KEY.
        mailbox.setPasswordEnc(secretCipher.encrypt(properties.getPassword()));
        mailbox.setEnabled(true);
        TenantMailbox saved = mailboxes.save(mailbox);
        log.info("Demo mailbox {} {} → IMAPS {}:{}, SMTP {}:{} (tenant {})", address,
                created ? "created" : "updated", properties.getImapHost(), properties.getImapPort(),
                properties.getSmtpHost(), properties.getSmtpPort(), tenantId);

        // Mirrors MailSenderProvider.resolve(): replies leave through the tenant's oldest enabled
        // SMTP mailbox — warn if that is not the demo one (then Mailpit never sees them).
        mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()
                .filter(sender -> !sender.getId().equals(saved.getId()))
                .ifPresent(sender -> log.warn("Tenant {} has an older SMTP mailbox {} — replies will "
                        + "go out through it, NOT Mailpit. Disable it on /settings for the demo.",
                        tenantId, sender.getUsername()));
    }
}
