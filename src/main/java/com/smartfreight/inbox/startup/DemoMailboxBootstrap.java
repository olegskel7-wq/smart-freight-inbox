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
 * <p>Harness off → the demo rows are DISABLED, not left running. That matters beyond noise:
 * a demo row is the tenant's only SMTP-configured mailbox, so {@code MailSenderProvider}
 * would keep routing that tenant's real replies to a Mailpit that no longer exists.
 */
@Component
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
        if (!properties.isEnabled()) {
            properties.getInboxes().forEach(this::disableIfPresent);
            return;
        }
        if (!secretCipher.isConfigured()) {
            log.warn("DEMO_MAIL_ENABLED is set but MAILBOX_ENCRYPTION_KEY is not — demo mailboxes "
                    + "cannot be stored. Set the key; no demo adapter will run until then.");
            return;
        }
        for (Map.Entry<Long, String> inbox : properties.getInboxes().entrySet()) {
            Long tenantId = inbox.getKey();
            if (!TenantContext.callAsSystem(() -> tenants.existsById(tenantId))) {
                log.warn("Demo mailbox {} skipped: tenant {} does not exist (seed it with the "
                        + "dev-seed V31 demo dataset first)", inbox.getValue(), tenantId);
                continue;
            }
            TenantContext.runAs(tenantId, () -> upsert(tenantId, inbox.getValue()));
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
        mailboxes.save(mailbox);
        log.info("Demo mailbox {} {} → IMAPS {}:{}, SMTP {}:{} (tenant {})", address,
                created ? "created" : "updated", properties.getImapHost(), properties.getImapPort(),
                properties.getSmtpHost(), properties.getSmtpPort(), tenantId);

        mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc()
                .filter(sender -> !sender.getId().equals(mailbox.getId()))
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

    private void disableIfPresent(Long tenantId, String address) {
        if (!TenantContext.callAsSystem(() -> tenants.existsById(tenantId))) {
            return;
        }
        TenantContext.runAs(tenantId, () -> mailboxes.findFirstByUsernameOrderByIdAsc(address)
                .filter(TenantMailbox::isEnabled)
                .ifPresent(mailbox -> {
                    mailbox.setEnabled(false);
                    mailboxes.save(mailbox);
                    log.info("Demo mail harness is off — disabled demo mailbox {} (tenant {})",
                            address, tenantId);
                }));
    }
}
