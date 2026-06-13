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
 * default. If the tenant has an enabled {@code tenant_mailbox} row with SMTP configured
 * ({@code smtp_host} set), replies are sent through THAT account, FROM the tenant's own
 * address; otherwise they fall back to the single platform SMTP account from {@code smtp.*}
 * env ({@link com.smartfreight.inbox.config.SmtpConfig}'s bean). Tenants that only set up
 * inbound IMAP keep working unchanged.
 *
 * <p>One {@link JavaMailSender} per distinct SMTP config, cached and keyed by a fingerprint
 * (host|port|username|encrypted-password-hash) — an edited mailbox rebuilds its sender on
 * the next send, the same change-detection idea {@code MailboxImapAdapterManager} uses for
 * IMAP adapters. The SMTP password reuses the mailbox's AES-GCM {@code passwordEnc}: one
 * account, one credential.
 *
 * <p>Must be called inside a tenant context — {@link TenantMailboxRepository}'s lookup is
 * {@code @TenantId}-filtered. Every send site (operator UI, auto-send, proactive scheduler)
 * already runs inside one.
 */
@Service
public class MailSenderProvider {

    private static final Logger log = LoggerFactory.getLogger(MailSenderProvider.class);

    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;
    private final JavaMailSender platformSender;
    private final String platformFrom;

    /** SMTP config fingerprint → built sender. Bounded by the number of distinct tenant accounts. */
    private final Map<String, JavaMailSender> senders = new ConcurrentHashMap<>();

    public MailSenderProvider(TenantMailboxRepository mailboxes,
                              SecretCipher secretCipher,
                              JavaMailSender platformSender,
                              @Value("${smtp.username}") String platformFrom) {
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
        this.platformSender = platformSender;
        this.platformFrom = platformFrom;
    }

    /** The sender + from-address for the current tenant (platform account if none configured). */
    public ResolvedSender resolve() {
        if (secretCipher.isConfigured()) {
            Optional<TenantMailbox> mailbox = mailboxes.findFirstByEnabledTrueAndSmtpHostIsNotNullOrderByIdAsc();
            if (mailbox.isPresent()) {
                TenantMailbox m = mailbox.get();
                JavaMailSender sender = senders.computeIfAbsent(fingerprint(m), fp -> buildSender(m));
                String from = (m.getFromAddress() != null && !m.getFromAddress().isBlank())
                        ? m.getFromAddress() : m.getUsername();
                return new ResolvedSender(sender, from);
            }
        }
        return new ResolvedSender(platformSender, platformFrom);
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

    /** A resolved SMTP transport and the address replies go out FROM. */
    public record ResolvedSender(JavaMailSender sender, String from) {
    }
}
