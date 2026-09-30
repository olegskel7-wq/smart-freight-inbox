package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.service.MailTlsPolicy;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.context.IntegrationFlowContext;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the fleet of dynamic IMAP IDLE adapters — one per enabled {@link TenantMailbox}
 * (Phase 2.2 S3).
 *
 * <p>Replaces the single env-configured adapter from the old {@code ImapConfig}: flows are
 * registered at runtime through {@link IntegrationFlowContext}, so onboarding a tenant's
 * mailbox needs no restart. {@code MailboxReconciler} drives this class — it periodically
 * diffs the DB rows against what is running and calls {@link #reconcile}.
 *
 * <p>Each flow binds the owning tenant BEFORE ingestion:
 * {@code TenantContext.runAs(tenantId, ...)} wraps the
 * {@link EmailIngestionService#processIncomingEmail} call, so the ticket insert, the
 * per-tenant message-id dedup and the outbox event are all attributed to the mailbox's
 * tenant. This closes the S1 gap where ingestion ran unset (= default tenant) — under S3
 * fail-loud semantics an unbound ingestion would throw at the first DB touch. The mailbox
 * id is passed along too and stored on the ticket, so the reply goes out through the inbox
 * the enquiry came in on.
 *
 * <p>Config changes are detected by fingerprint: an edited row (host, port, username,
 * password, enabled tenant) gets its adapter dropped and rebuilt. The IMAP password is
 * decrypted only here, at adapter build time, and lives solely inside the JavaMail
 * session — never in a field.
 */
@Component
public class MailboxImapAdapterManager {

    private static final Logger log = LoggerFactory.getLogger(MailboxImapAdapterManager.class);

    private static final String FLOW_ID_PREFIX = "imap-mailbox-";

    private final IntegrationFlowContext flowContext;
    private final EmailIngestionService emailIngestionService;
    private final SecretCipher secretCipher;
    private final MailTlsPolicy mailTlsPolicy;

    /** mailbox id → fingerprint of the config the running adapter was built from. */
    private final Map<Long, String> running = new ConcurrentHashMap<>();

    public MailboxImapAdapterManager(IntegrationFlowContext flowContext,
                                     EmailIngestionService emailIngestionService,
                                     SecretCipher secretCipher,
                                     MailTlsPolicy mailTlsPolicy,
                                     MeterRegistry meterRegistry) {
        this.flowContext = flowContext;
        this.emailIngestionService = emailIngestionService;
        this.secretCipher = secretCipher;
        this.mailTlsPolicy = mailTlsPolicy;
        Gauge.builder("smartfreight_imap_adapters_active", running, Map::size)
                .description("IMAP IDLE adapters currently registered (one per enabled tenant mailbox)")
                .register(meterRegistry);
    }

    /**
     * Brings the running adapter fleet in line with the desired mailbox list:
     * missing adapters are started, edited ones rebuilt, removed/disabled ones stopped.
     * One broken mailbox (bad config, registration failure) never blocks the others.
     */
    public synchronized void reconcile(java.util.List<TenantMailbox> desired) {
        Set<Long> desiredIds = new java.util.HashSet<>();
        for (TenantMailbox mailbox : desired) {
            desiredIds.add(mailbox.getId());
            String fingerprint = fingerprint(mailbox);
            if (fingerprint.equals(running.get(mailbox.getId()))) {
                continue;
            }
            stop(mailbox.getId());
            start(mailbox, fingerprint);
        }
        for (Long staleId : new java.util.HashSet<>(running.keySet())) {
            if (!desiredIds.contains(staleId)) {
                stop(staleId);
            }
        }
    }

    /** Stops every adapter — context shutdown hook, keeps IMAP connections from leaking. */
    public synchronized void stopAll() {
        for (Long id : new java.util.HashSet<>(running.keySet())) {
            stop(id);
        }
    }

    /** Whether the mailbox currently has a registered adapter (surfaced on the settings UI). */
    public boolean isRunning(Long mailboxId) {
        return running.containsKey(mailboxId);
    }

    private void start(TenantMailbox mailbox, String fingerprint) {
        if (!secretCipher.isConfigured()) {
            log.warn("Mailbox {} (tenant {}): MAILBOX_ENCRYPTION_KEY is not set — cannot decrypt "
                    + "credentials, adapter not started", mailbox.getId(), mailbox.getTenantId());
            return;
        }
        try {
            String password = secretCipher.decrypt(mailbox.getPasswordEnc());
            IntegrationFlow flow = buildFlow(mailbox, password);
            flowContext.registration(flow).id(FLOW_ID_PREFIX + mailbox.getId()).register();
            running.put(mailbox.getId(), fingerprint);
            log.info("IMAP adapter started: mailbox {} ({}@{}:{}) for tenant {}",
                    mailbox.getId(), mailbox.getUsername(), mailbox.getHost(), mailbox.getPort(),
                    mailbox.getTenantId());
        } catch (Exception e) {
            // Stay down for this row; the next reconcile pass retries (fingerprint not recorded).
            log.error("Failed to start IMAP adapter for mailbox {} (tenant {})",
                    mailbox.getId(), mailbox.getTenantId(), e);
        }
    }

    private void stop(Long mailboxId) {
        if (running.remove(mailboxId) == null) {
            return;
        }
        try {
            flowContext.remove(FLOW_ID_PREFIX + mailboxId);
            log.info("IMAP adapter stopped: mailbox {}", mailboxId);
        } catch (Exception e) {
            log.error("Failed to stop IMAP adapter for mailbox {}", mailboxId, e);
        }
    }

    /**
     * The same IMAP IDLE pipeline the old static {@code ImapConfig} built, parameterised
     * by mailbox row — plus the tenant binding around ingestion.
     */
    private IntegrationFlow buildFlow(TenantMailbox mailbox, String password) {
        Long tenantId = mailbox.getTenantId();
        Long mailboxId = mailbox.getId();
        String url = String.format("imaps://%s:%s@%s:%d/INBOX",
                encode(mailbox.getUsername()), encode(password), mailbox.getHost(), mailbox.getPort());

        return IntegrationFlow
                .from(Mail.imapIdleAdapter(url)
                        .javaMailProperties(javaMailProperties(mailbox, password))
                        .shouldMarkMessagesAsRead(true)
                        .shouldDeleteMessages(false)
                        .autoStartup(true))
                .handle(message -> ingest(tenantId, mailboxId, (MimeMessage) message.getPayload()))
                .get();
    }

    /** The adapter's JavaMail session config; TLS verification comes from {@link MailTlsPolicy}. */
    Properties javaMailProperties(TenantMailbox mailbox, String password) {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.user", mailbox.getUsername());
        p.put("mail.imaps.password", password);
        p.put("mail.imaps.auth", "true");
        p.put("mail.imaps.connectiontimeout", "10000");
        p.put("mail.imaps.timeout", "60000");
        p.put("mail.imaps.keepalive.interval", "60000");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.partialfetch", "false");
        p.put("mail.imaps.fetchsize", "1048576");
        mailTlsPolicy.applyImaps(p, mailbox.getHost());
        return p;
    }

    /** One arrived message → ticket, inside the mailbox's tenant and attributed to the mailbox. */
    void ingest(Long tenantId, Long mailboxId, MimeMessage mimeMessage) {
        TenantContext.runAs(tenantId, () -> emailIngestionService.processIncomingEmail(mimeMessage, mailboxId));
    }

    private static String fingerprint(TenantMailbox mailbox) {
        return mailbox.getTenantId() + "|" + mailbox.getHost() + "|" + mailbox.getPort()
                + "|" + mailbox.getUsername() + "|" + mailbox.getPasswordEnc().hashCode();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
