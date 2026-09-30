package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * CRUD facade for the current tenant's mailboxes, used by the {@code /settings} UI
 * (Phase 2.2 S3).
 *
 * <p>All reads and writes run inside the caller's tenant scope — {@code @TenantId}
 * filters queries and stamps inserts, RLS (V21) backs it up below the ORM. Passwords
 * are encrypted here ({@link SecretCipher}) so the controller and the entity never see
 * the scheme; rotation = {@link #update} in place, so tickets keep their mailbox link.
 *
 * <p>Changes take effect without a restart: {@code MailboxReconciler} picks them up on
 * its next pass (default ≤30 s) and starts/stops/rebuilds the IMAP adapters to match.
 */
@Service
public class MailboxManagementService {

    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;

    public MailboxManagementService(TenantMailboxRepository mailboxes, SecretCipher secretCipher) {
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
    }

    /** The current tenant's mailboxes, oldest first. */
    public List<TenantMailbox> findAll() {
        return mailboxes.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * Adds a mailbox for the current tenant.
     *
     * <p>SMTP is optional: pass a blank {@code smtpHost} for an inbound-only mailbox, whose
     * replies fall back to the platform SMTP account. When set, the SMTP password reuses the
     * IMAP one ({@code rawPassword}) — one account, one credential.
     *
     * @throws IllegalArgumentException on blank/invalid input
     * @throws IllegalStateException    when MAILBOX_ENCRYPTION_KEY is missing
     */
    @Transactional
    public void create(String host, int port, String username, String rawPassword,
                       String smtpHost, Integer smtpPort, String fromAddress) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Host, username and password are all required.");
        }
        TenantMailbox mailbox = new TenantMailbox();
        applyConfig(mailbox, host, port, username, smtpHost, smtpPort, fromAddress);
        mailbox.setPasswordEnc(secretCipher.encrypt(rawPassword));
        mailbox.setEnabled(true);
        mailboxes.save(mailbox);
    }

    /**
     * Edits a mailbox in place (Gate B15) — the row keeps its id, so tickets that arrived on it
     * ({@code email_ticket.mailbox_id}, V37) keep replying through it after a password rotation.
     * Delete + re-add would null that link ({@code ON DELETE SET NULL}).
     *
     * <p>Same validation as {@link #create}. A blank {@code rawPassword} keeps the stored one (the
     * form never shows it) — but only while the password keeps going where it went before: any
     * change to the IMAP host/port/username, or pointing SMTP at a new host/port, needs the password
     * re-entered. Otherwise a caller who never knew the password could re-point the mailbox at a
     * server they control and the adapter would log in there with the decrypted stored one (security
     * review of B15). Dropping SMTP or changing only the from-address stays password-free. A blank
     * {@code smtpHost} makes the mailbox inbound-only again. The enabled flag is untouched — pausing
     * is {@link #setEnabled}. The IMAP adapter and the cached SMTP sender are keyed by a config
     * fingerprint, so both rebuild on their own.
     *
     * @throws MailboxNotFoundException if the id is not one of the caller's mailboxes
     * @throws IllegalArgumentException on blank/invalid input, or a re-pointed mailbox without a password
     * @throws IllegalStateException    when a new password is given but MAILBOX_ENCRYPTION_KEY is missing
     */
    @Transactional
    public void update(Long id, String host, int port, String username, String rawPassword,
                       String smtpHost, Integer smtpPort, String fromAddress) {
        TenantMailbox mailbox = findInCurrentTenant(id);
        Destination before = Destination.of(mailbox);
        applyConfig(mailbox, host, port, username, smtpHost, smtpPort, fromAddress);
        if (rawPassword != null && !rawPassword.isBlank()) {
            mailbox.setPasswordEnc(secretCipher.encrypt(rawPassword));
        } else if (before.repointedBy(Destination.of(mailbox))) {
            // Thrown after applyConfig mutated the managed entity — the transaction rolls it back.
            throw new IllegalArgumentException(
                    "Re-enter the password when changing the server, port, username or SMTP settings.");
        }
        // Flush now so a (host, username) clash surfaces here as DataIntegrityViolationException
        // (→ 409 in the controller), not later at commit.
        mailboxes.saveAndFlush(mailbox);
    }

    /**
     * Pauses or resumes a mailbox without touching its credentials.
     *
     * @throws IllegalArgumentException if the id is not one of the caller's mailboxes
     */
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        TenantMailbox mailbox = findInCurrentTenant(id);
        mailbox.setEnabled(enabled);
        mailboxes.save(mailbox);
    }

    /**
     * Deletes a mailbox; the reconciler stops its adapter on the next pass.
     *
     * @throws IllegalArgumentException if the id is not one of the caller's mailboxes
     */
    @Transactional
    public void delete(Long id) {
        mailboxes.delete(findInCurrentTenant(id));
    }

    /** Validates and copies the fields shared by {@link #create} and {@link #update}. */
    private static void applyConfig(TenantMailbox mailbox, String host, int port, String username,
                                    String smtpHost, Integer smtpPort, String fromAddress) {
        if (host == null || host.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Host, username and password are all required.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        mailbox.setHost(host.trim());
        mailbox.setPort(port);
        mailbox.setUsername(username.trim());

        if (smtpHost != null && !smtpHost.isBlank()) {
            int resolvedSmtpPort = smtpPort != null ? smtpPort : 587;
            if (resolvedSmtpPort < 1 || resolvedSmtpPort > 65535) {
                throw new IllegalArgumentException("SMTP port must be between 1 and 65535.");
            }
            mailbox.setSmtpHost(smtpHost.trim());
            mailbox.setSmtpPort(resolvedSmtpPort);
            mailbox.setFromAddress(fromAddress != null && !fromAddress.isBlank()
                    ? fromAddress.trim() : null);
        } else {
            // Inbound-only: replies fall back to the platform account.
            mailbox.setSmtpHost(null);
            mailbox.setSmtpPort(null);
            mailbox.setFromAddress(null);
        }
    }

    /** Where the stored password is sent: the IMAP login and, when set, the SMTP AUTH. */
    private record Destination(String host, int port, String username, String smtpHost, Integer smtpPort) {

        static Destination of(TenantMailbox m) {
            return new Destination(m.getHost(), m.getPort(), m.getUsername(), m.getSmtpHost(), m.getSmtpPort());
        }

        /** True if {@code next} would send the password to an endpoint this one did not. */
        boolean repointedBy(Destination next) {
            boolean imap = !host.equals(next.host) || port != next.port || !username.equals(next.username);
            boolean smtp = next.smtpHost != null
                    && (!next.smtpHost.equals(smtpHost) || !Objects.equals(next.smtpPort, smtpPort));
            return imap || smtp;
        }
    }

    /** Tenant-filtered load — a foreign tenant's id reads as "not found". */
    private TenantMailbox findInCurrentTenant(Long id) {
        return mailboxes.findById(id)
                .orElseThrow(() -> new MailboxNotFoundException(id));
    }

    /**
     * The id is not one of the caller's mailboxes. Extends {@link IllegalArgumentException} so the
     * toggle/delete handlers keep mapping it to 404; the update handler catches it first to tell it
     * apart from a validation error (400).
     */
    public static class MailboxNotFoundException extends IllegalArgumentException {
        public MailboxNotFoundException(Long id) {
            super("Mailbox not found: " + id);
        }
    }
}
