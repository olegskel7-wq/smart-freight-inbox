package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the outbound SMTP sender for the CURRENT tenant (Phase 2.2 SMTP close-out).
 *
 * <p>Mirrors {@link OllamaClientProvider}: a per-tenant override on top of a platform
 * default. Resolution order, first usable wins (usable = enabled with {@code smtp_host} set):
 * <ol>
 *   <li>the mailbox the ticket arrived on ({@code email_ticket.mailbox_id}, V37) — a tenant
 *       may watch several inboxes, and the customer must hear back FROM the address they
 *       wrote to, through that same account;</li>
 *   <li>the tenant's oldest SMTP mailbox — for tickets with no mailbox (pre-V37, seeded) or
 *       one that is disabled, SMTP-less or deleted;</li>
 *   <li>the single platform SMTP account from {@code smtp.*} env
 *       ({@link com.smartfreight.inbox.config.SmtpConfig}'s bean) — tenants that only set up
 *       inbound IMAP keep working unchanged.</li>
 * </ol>
 *
 * <p>One {@link JavaMailSender} per mailbox, cached with the fingerprint of the config it was
 * built from (host|port|username|encrypted-password-hash) — an edited mailbox replaces its sender
 * on the next send, the same change-detection idea {@code MailboxImapAdapterManager} uses for
 * IMAP adapters. The SMTP password reuses the mailbox's AES-GCM {@code passwordEnc}: one
 * account, one credential.
 *
 * <p>Must be called inside a tenant context — {@link TenantMailboxRepository}'s lookups are
 * {@code @TenantId}-filtered (and RLS-guarded), so a mailbox id of another tenant resolves to
 * nothing and falls through to this tenant's own default. Every send site (operator UI,
 * auto-send, proactive scheduler) already runs inside one.
 */
@Service
public class MailSenderProvider {

    private static final Logger log = LoggerFactory.getLogger(MailSenderProvider.class);

    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;
    private final JavaMailSender platformSender;
    private final String platformFrom;

    /**
     * Mailbox id → the sender built from its current config. Keyed by id (ids are global), not by
     * fingerprint, so an in-place edit (Gate B15) REPLACES the old sender — and the stale decrypted
     * password inside it — instead of piling up one entry per rotation. Bounded by the mailbox count.
     */
    private final Map<Long, CachedSender> senders = new ConcurrentHashMap<>();

    public MailSenderProvider(TenantMailboxRepository mailboxes,
                              SecretCipher secretCipher,
                              JavaMailSender platformSender,
                              @Value("${smtp.username}") String platformFrom) {
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
        this.platformSender = platformSender;
        this.platformFrom = platformFrom;
    }

    /**
     * The sender + from-address for a reply in the current tenant.
     *
     * @param mailboxId the mailbox the ticket arrived on; null → the tenant's default sender
     */
    public ResolvedSender resolve(Long mailboxId) {
        if (secretCipher.isConfigured()) {
            Optional<TenantMailbox> mailbox = Optional.ofNullable(mailboxId)
                    .flatMap(mailboxes::findByIdAndEnabledTrueAndSmtpHostIsNotNull)
                    .or(mailboxes::findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc);
            if (mailbox.isPresent()) {
                TenantMailbox m = mailbox.get();
                JavaMailSender sender = senderFor(m);
                String from = (m.getFromAddress() != null && !m.getFromAddress().isBlank())
                        ? m.getFromAddress() : m.getUsername();
                return new ResolvedSender(sender, from);
            }
        }
        return new ResolvedSender(platformSender, platformFrom);
    }

    /** Lock-free on a hit (every send); compute() only when the mailbox is new or was edited. */
    private JavaMailSender senderFor(TenantMailbox m) {
        String fingerprint = fingerprint(m);
        CachedSender cached = senders.get(m.getId());
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.sender();
        }
        return senders.compute(m.getId(), (id, current) ->
                current != null && current.fingerprint().equals(fingerprint)
                        ? current : new CachedSender(fingerprint, buildSender(m))).sender();
    }

    /** How many mailbox senders are cached — lets tests check a rotation replaces rather than adds. */
    int cachedSenderCount() {
        return senders.size();
    }

    private JavaMailSender buildSender(TenantMailbox m) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(m.getSmtpHost());
        sender.setPort(m.getSmtpPort() != null ? m.getSmtpPort() : 587);
        sender.setUsername(m.getUsername());
        sender.setPassword(secretCipher.decrypt(m.getPasswordEnc()));

        // Same STARTTLS/timeout profile as the platform SmtpConfig bean.
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.trust", m.getSmtpHost());
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        log.info("Built per-tenant SMTP sender for {}:{} (user {})",
                m.getSmtpHost(), sender.getPort(), m.getUsername());
        return sender;
    }

    private static String fingerprint(TenantMailbox m) {
        return m.getTenantId() + "|" + m.getSmtpHost() + "|" + m.getSmtpPort()
                + "|" + m.getUsername() + "|" + m.getPasswordEnc().hashCode();
    }

    /** A built sender and the config fingerprint it was built from. */
    private record CachedSender(String fingerprint, JavaMailSender sender) {
    }

    /** A resolved SMTP transport and the address replies go out FROM. */
    public record ResolvedSender(JavaMailSender sender, String from) {
    }
}
