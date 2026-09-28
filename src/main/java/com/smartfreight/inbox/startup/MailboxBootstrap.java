package com.smartfreight.inbox.startup;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenancyProperties;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time import of the legacy env-configured mailbox into {@code tenant_mailbox}
 * (Phase 2.2 S3).
 *
 * <p>Before S3 the single IMAP inbox lived in {@code spring.mail.*} env vars and a static
 * adapter. Now ALL adapters come from {@code tenant_mailbox} rows — so an existing
 * deployment's env mailbox is imported as the Default tenant's first row at startup,
 * password encrypted. Idempotent: it only fires while the Default tenant has NO mailbox
 * rows, so user edits are never overwritten. (Corollary: deleting the tenant's last
 * mailbox re-imports the env one on restart — remove the IMAP_* env vars once the DB is
 * the source of truth.)
 *
 * <p>Placeholder env values ({@code imap.example.com} / {@code changeme}) mean "nothing
 * to import" — same convention the old static {@code ImapConfig} used to disable itself.
 */
@Component
@Order(MailboxBootstrap.ORDER)
public class MailboxBootstrap implements ApplicationRunner {

    /** Runs before {@link DemoMailboxBootstrap}: a demo row must never pre-empt the env import. */
    static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(MailboxBootstrap.class);

    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;
    private final TenancyProperties tenancyProperties;

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port:993}")
    private int port;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    /** Same kill switch the old static adapter honoured. */
    @Value("${imap.enabled:true}")
    private boolean imapEnabled;

    public MailboxBootstrap(TenantMailboxRepository mailboxes,
                            SecretCipher secretCipher,
                            TenancyProperties tenancyProperties) {
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
        this.tenancyProperties = tenancyProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean configured = imapEnabled
                && !host.equals("imap.example.com")
                && !password.equals("changeme");
        if (!configured) {
            log.info("No env-configured mailbox to import — mailboxes are managed on /settings.");
            return;
        }
        if (!secretCipher.isConfigured()) {
            log.warn("IMAP_* env vars are set but MAILBOX_ENCRYPTION_KEY is not — cannot import "
                    + "the mailbox into tenant_mailbox. Set the key (openssl rand -base64 32); "
                    + "no IMAP adapter will run until then.");
            return;
        }

        TenantContext.runAs(tenancyProperties.getDefaultId(), () -> {
            if (mailboxes.count() > 0) {
                return; // already imported (or managed in the UI) — never overwrite
            }
            TenantMailbox mailbox = new TenantMailbox();
            mailbox.setHost(host);
            mailbox.setPort(port);
            mailbox.setUsername(username);
            mailbox.setPasswordEnc(secretCipher.encrypt(password));
            mailbox.setEnabled(true);
            mailboxes.save(mailbox);
            log.info("Imported env-configured mailbox {}@{}:{} as Default-tenant mailbox; "
                    + "manage it on /settings from now on.", username, host, port);
        });
    }
}
