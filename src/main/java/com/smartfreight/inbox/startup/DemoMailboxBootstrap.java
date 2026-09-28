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

import java.util.Map;

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
            if (!secretCipher.isConfigured()) {
                log.warn("DEMO_MAIL_ENABLED is set but MAILBOX_ENCRYPTION_KEY is not — demo mailboxes "
                        + "cannot be stored. Set the key; no demo adapter will run until then.");
                return;
            }
            if (!validPort(properties.getImapPort()) || !validPort(properties.getSmtpPort())) {
                log.warn("Demo mail harness not started: invalid port (IMAP {}, SMTP {})",
                        properties.getImapPort(), properties.getSmtpPort());
                return;
            }
        }
        for (Map.Entry<Long, String> inbox : properties.getInboxes().entrySet()) {
            Long tenantId = inbox.getKey();
            String address = inbox.getValue();
            try {
                if (!TenantContext.callAsSystem(() -> tenants.existsById(tenantId))) {
                    if (properties.isEnabled()) {
                        log.warn("Demo mailbox {} skipped: tenant {} does not exist (seed it with the "
                                + "dev-seed V31 demo dataset first)", address, tenantId);
                    }
                    continue;
                }
                if (properties.isEnabled()) {
                    TenantContext.runAs(tenantId, () -> upsert(tenantId, address));
                } else {
                    TenantContext.runAs(tenantId, () -> removeIfPresent(tenantId, address));
                }
            } catch (RuntimeException e) {
                log.error("Demo mailbox {} (tenant {}) could not be {}", address, tenantId,
                        properties.isEnabled() ? "set up" : "removed", e);
            }
        }
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
        // Re-encrypt only on change: a fresh IV every start would alter the fingerprint and
        // force a needless adapter rebuild on each restart.
        if (created || !storedPasswordMatches(mailbox)) {
            mailbox.setPasswordEnc(secretCipher.encrypt(properties.getPassword()));
        }
        mailbox.setEnabled(true);
        TenantMailbox saved = mailboxes.save(mailbox);
        log.info("Demo mailbox {} {} → IMAPS {}:{}, SMTP {}:{} (tenant {})", address,
                created ? "created" : "updated", properties.getImapHost(), properties.getImapPort(),
                properties.getSmtpHost(), properties.getSmtpPort(), tenantId);

        mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()
                .filter(sender -> !sender.getId().equals(saved.getId()))
                .ifPresent(sender -> log.warn("Tenant {} has an older SMTP mailbox {} — replies will "
                        + "go out through it, NOT Mailpit. Disable it on /settings for the demo.",
                        tenantId, sender.getUsername()));
    }

    /** False also when the row was encrypted under a since-rotated key — then just re-encrypt. */
    private boolean storedPasswordMatches(TenantMailbox mailbox) {
        try {
            return properties.getPassword().equals(secretCipher.decrypt(mailbox.getPasswordEnc()));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void removeIfPresent(Long tenantId, String address) {
        mailboxes.findFirstByUsernameOrderByIdAsc(address).ifPresent(mailbox -> {
            mailboxes.delete(mailbox);
            log.info("Demo mail harness is off — removed demo mailbox {} (tenant {})", address, tenantId);
        });
    }

    private static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }
}
