package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.MailboxAuthType;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.repository.TenantMs365DirectoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * CRUD facade for the current tenant's mailboxes, used by the {@code /settings} UI.
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
    private final EmailTicketRepository tickets;
    private final ShipmentWatchRepository watches;
    private final SecretCipher secretCipher;
    private final TenantMs365DirectoryRepository directories;

    public MailboxManagementService(TenantMailboxRepository mailboxes, EmailTicketRepository tickets,
                                    ShipmentWatchRepository watches, SecretCipher secretCipher,
                                    TenantMs365DirectoryRepository directories) {
        this.mailboxes = mailboxes;
        this.tickets = tickets;
        this.watches = watches;
        this.secretCipher = secretCipher;
        this.directories = directories;
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
    public void create(String host, Integer port, String username, String rawPassword,
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
     * Edits a mailbox in place — the row keeps its id, so tickets that arrived on it
     * ({@code email_ticket.mailbox_id}, V37) keep replying through it after a password rotation.
     * Delete + re-add would null that link ({@code ON DELETE SET NULL}).
     *
     * <p>Same validation as {@link #create}. A blank {@code rawPassword} keeps the stored one (the
     * form never shows it) — but only while the password keeps going where it went before: any
     * change to the IMAP host/port/username, or pointing SMTP at a new host/port, needs the password
     * re-entered. Otherwise a caller who never knew the password could re-point the mailbox at a
     * server they control and the adapter would log in there with the decrypted stored one (found in
     * security review). Dropping SMTP or changing only the from-address stays password-free. A blank
     * {@code smtpHost} makes the mailbox inbound-only again. The enabled flag is untouched — pausing
     * is {@link #setEnabled}. The IMAP adapter and the cached SMTP sender each remember the config
     * fingerprint they were built from, so both rebuild on their own.
     *
     * <p>The username IS the account: once mail has arrived on the mailbox (a ticket or a status
     * watch points at it) it can no longer be renamed — otherwise customers who wrote to the old
     * address would get replies from a different one, the very thing V37 fixed. A typo fixed before
     * the first email, or a case-only change, stays allowed; a new address is a new mailbox.
     *
     * @throws MailboxNotFoundException if the id is not one of the caller's mailboxes
     * @throws MailboxInUseException    when renaming the account of a mailbox that has received mail
     * @throws IllegalArgumentException on blank/invalid input, or a re-pointed mailbox without a password
     * @throws IllegalStateException    when a new password is given but MAILBOX_ENCRYPTION_KEY is missing
     */
    @Transactional
    public void update(Long id, String host, Integer port, String username, String rawPassword,
                       String smtpHost, Integer smtpPort, String fromAddress) {
        TenantMailbox mailbox = findInCurrentTenant(id);
        if (mailbox.getAuthType() == MailboxAuthType.MS365_OAUTH) {
            throw new IllegalArgumentException("This is a Microsoft 365 mailbox — edit it with the Microsoft 365 form.");
        }
        Destination before = Destination.of(mailbox);
        applyConfig(mailbox, host, port, username, smtpHost, smtpPort, fromAddress);
        if (!mailbox.getUsername().equalsIgnoreCase(before.username())
                && (tickets.existsByMailboxId(id) || watches.existsByMailboxId(id))) {
            throw new MailboxInUseException();
        }
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

    /** Exchange Online's fixed endpoints for an MS365_OAUTH mailbox (XOAUTH2 on both). */
    static final String MS365_IMAP_HOST = "outlook.office365.com";
    static final int MS365_IMAP_PORT = 993;
    static final String MS365_SMTP_HOST = "smtp.office365.com";
    static final int MS365_SMTP_PORT = 587;

    /**
     * Adds a Microsoft 365 mailbox (V38) under a directory this tenant connected through the consent
     * flow — never under a typed-in one: {@code directoryId} must be one of the tenant's bindings
     * (and the composite FK enforces the same in the database). No password exists for it; IMAP and
     * SMTP are Exchange Online's fixed endpoints, SMTP only when {@code sendReplies}.
     *
     * @throws IllegalArgumentException on blank input or a directory the tenant has not connected
     */
    @Transactional
    public void createMs365(String directoryId, String username, boolean sendReplies, String fromAddress) {
        TenantMailbox mailbox = new TenantMailbox();
        applyMs365(mailbox, directoryId, username, sendReplies, fromAddress);
        mailbox.setEnabled(true);
        mailboxes.saveAndFlush(mailbox);
    }

    /**
     * Edits a Microsoft 365 mailbox in place (V38): its directory (among the tenant's bindings), the
     * address, whether it sends replies, and the from-address. The address follows the same freeze as
     * a password mailbox — once mail has arrived, a new address is a new mailbox.
     *
     * @throws MailboxNotFoundException if the id is not one of the caller's mailboxes
     * @throws MailboxInUseException    when renaming a mailbox that has received mail
     * @throws IllegalArgumentException on blank input, a directory the tenant has not connected, or a
     *                                  password mailbox (edited with {@link #update})
     */
    @Transactional
    public void updateMs365(Long id, String directoryId, String username, boolean sendReplies, String fromAddress) {
        TenantMailbox mailbox = findInCurrentTenant(id);
        if (mailbox.getAuthType() != MailboxAuthType.MS365_OAUTH) {
            throw new IllegalArgumentException("This mailbox signs in with a password — edit it with the mailbox form.");
        }
        String before = mailbox.getUsername();
        applyMs365(mailbox, directoryId, username, sendReplies, fromAddress);
        if (!mailbox.getUsername().equalsIgnoreCase(before)
                && (tickets.existsByMailboxId(id) || watches.existsByMailboxId(id))) {
            throw new MailboxInUseException();
        }
        mailboxes.saveAndFlush(mailbox);
    }

    private void applyMs365(TenantMailbox mailbox, String directoryId, String username,
                            boolean sendReplies, String fromAddress) {
        if (username == null || username.isBlank() || !username.contains("@")) {
            throw new IllegalArgumentException("The mailbox address is required (e.g. ops@yourcompany.co.uk).");
        }
        String directory = directoryId == null ? "" : directoryId.trim().toLowerCase(java.util.Locale.ROOT);
        if (directory.isEmpty() || directories.findByDirectoryId(directory).isEmpty()) {
            throw new IllegalArgumentException(
                    "Connect this Microsoft 365 organisation first (Settings → Mailboxes → Connect Microsoft 365).");
        }
        mailbox.setAuthType(MailboxAuthType.MS365_OAUTH);
        mailbox.setMsDirectoryId(directory);
        mailbox.setPasswordEnc(null);
        mailbox.setHost(MS365_IMAP_HOST);
        mailbox.setPort(MS365_IMAP_PORT);
        mailbox.setUsername(username.trim());
        if (sendReplies) {
            mailbox.setSmtpHost(MS365_SMTP_HOST);
            mailbox.setSmtpPort(MS365_SMTP_PORT);
            mailbox.setFromAddress(fromAddress != null && !fromAddress.isBlank() ? fromAddress.trim() : null);
        } else {
            mailbox.setSmtpHost(null);
            mailbox.setSmtpPort(null);
            mailbox.setFromAddress(null);
        }
    }

    /**
     * Pauses or resumes a mailbox without touching its credentials.
     *
     * @throws MailboxNotFoundException if the id is not one of the caller's mailboxes
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
     * @throws MailboxNotFoundException if the id is not one of the caller's mailboxes
     */
    @Transactional
    public void delete(Long id) {
        mailboxes.delete(findInCurrentTenant(id));
    }

    /** Validates and copies the fields shared by {@link #create} and {@link #update}. */
    private static void applyConfig(TenantMailbox mailbox, String host, Integer port, String username,
                                    String smtpHost, Integer smtpPort, String fromAddress) {
        if (host == null || host.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Host, username and password are all required.");
        }
        int resolvedPort = port != null ? port : 993;
        if (resolvedPort < 1 || resolvedPort > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        mailbox.setHost(host.trim());
        mailbox.setPort(resolvedPort);
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

        /**
         * True if {@code next} would send the password to an endpoint this one did not. Host names
         * and mail addresses are case-insensitive, so a case-only change is not a re-point.
         */
        boolean repointedBy(Destination next) {
            boolean imap = !host.equalsIgnoreCase(next.host) || port != next.port
                    || !username.equalsIgnoreCase(next.username);
            boolean smtp = next.smtpHost != null
                    && (!next.smtpHost.equalsIgnoreCase(smtpHost) || !Objects.equals(next.smtpPort, smtpPort));
            return imap || smtp;
        }
    }

    /** Tenant-filtered load — a foreign tenant's id reads as "not found". */
    private TenantMailbox findInCurrentTenant(Long id) {
        return mailboxes.findById(id)
                .orElseThrow(() -> new MailboxNotFoundException(id));
    }

    /** The id is not one of the caller's mailboxes (a foreign tenant's id included) — 404. */
    public static class MailboxNotFoundException extends RuntimeException {
        public MailboxNotFoundException(Long id) {
            super("Mailbox not found: " + id);
        }
    }

    /** The account (username) of a mailbox that has already received mail cannot be renamed. */
    public static class MailboxInUseException extends RuntimeException {
        public MailboxInUseException() {
            super("This mailbox has already received mail, so its username can't be changed — replies to "
                    + "those customers would come from a different address. Add the new address as a "
                    + "separate mailbox and disable this one.");
        }
    }
}
