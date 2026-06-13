package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD facade for the current tenant's mailboxes, used by the {@code /settings} UI
 * (Phase 2.2 S3).
 *
 * <p>All reads and writes run inside the caller's tenant scope — {@code @TenantId}
 * filters queries and stamps inserts, RLS (V21) backs it up below the ORM. Passwords
 * are encrypted here ({@link SecretCipher}) so the controller and the entity never see
 * the scheme; rotation = delete + re-add (the adapter rebuilds either way).
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
        if (host == null || host.isBlank()
                || username == null || username.isBlank()
                || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Host, username and password are all required.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        TenantMailbox mailbox = new TenantMailbox();
        mailbox.setHost(host.trim());
        mailbox.setPort(port);
        mailbox.setUsername(username.trim());
        mailbox.setPasswordEnc(secretCipher.encrypt(rawPassword));
        mailbox.setEnabled(true);

        if (smtpHost != null && !smtpHost.isBlank()) {
            int resolvedSmtpPort = smtpPort != null ? smtpPort : 587;
            if (resolvedSmtpPort < 1 || resolvedSmtpPort > 65535) {
                throw new IllegalArgumentException("SMTP port must be between 1 and 65535.");
            }
            mailbox.setSmtpHost(smtpHost.trim());
            mailbox.setSmtpPort(resolvedSmtpPort);
            mailbox.setFromAddress(fromAddress != null && !fromAddress.isBlank()
                    ? fromAddress.trim() : null);
        }
        mailboxes.save(mailbox);
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

    /** Tenant-filtered load — a foreign tenant's id reads as "not found". */
    private TenantMailbox findInCurrentTenant(Long id) {
        return mailboxes.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + id));
    }
}
