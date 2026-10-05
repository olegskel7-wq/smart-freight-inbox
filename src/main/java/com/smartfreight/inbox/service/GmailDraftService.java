package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.repository.TenantMailboxRepository;
import com.smartfreight.inbox.util.EmailAddressUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Properties;

/**
 * Gmail "Drafts" folder service — saves the AI-generated draft
 * directly to the operator's mailbox Drafts folder via IMAP APPEND.
 *
 * The operator opens Gmail → Drafts and sees the ready message with:
 *  - To: customer address
 *  - Subject: Re: &lt;original subject&gt;
 *  - Body: AI-generated text (editable before sending)
 *  - Threading: In-Reply-To / References → message is grouped into the original conversation
 *
 * The draft goes ONLY into the mailbox the enquiry arrived on ({@code email_ticket.mailbox_id},
 * V37), with that mailbox's own credentials. Until Oct 2026 it went into the single platform
 * account from {@code spring.mail.*} for EVERY tenant — one tenant's customer mail landing in
 * another account (cross-tenant leak). A ticket without a usable mailbox (pre-V37, seeded, the
 * mailbox deleted/disabled) gets no draft; there is deliberately no platform fallback.
 *
 * The lookup is tenant-filtered ({@code @TenantId} + RLS), so it must run inside the ticket's
 * tenant context — the pipeline consumer does. The connection is opened only to save the draft
 * and immediately closed. Errors are only logged — the ticket remains DRAFT_READY regardless.
 */
@Service
public class GmailDraftService {

    private static final Logger log = LoggerFactory.getLogger(GmailDraftService.class);

    private final SettingsService settingsService;
    private final TenantMailboxRepository mailboxes;
    private final SecretCipher secretCipher;
    private final MailTlsPolicy mailTlsPolicy;
    private final Counter draftSavedCounter;
    private final Counter draftFailedCounter;

    public GmailDraftService(SettingsService settingsService,
                             TenantMailboxRepository mailboxes,
                             SecretCipher secretCipher,
                             MailTlsPolicy mailTlsPolicy,
                             MeterRegistry meterRegistry) {
        this.settingsService = settingsService;
        this.mailboxes = mailboxes;
        this.secretCipher = secretCipher;
        this.mailTlsPolicy = mailTlsPolicy;
        this.draftSavedCounter = Counter.builder("smartfreight_gmail_draft_total")
                .description("Gmail draft save results")
                .tag("result", "saved")
                .register(meterRegistry);
        this.draftFailedCounter = Counter.builder("smartfreight_gmail_draft_total")
                .description("Gmail draft save results")
                .tag("result", "failed")
                .register(meterRegistry);
    }

    /**
     * Saves a draft to the Drafts folder of the ticket's own mailbox via IMAP APPEND.
     *
     * @param ticket ticket with the generated draft and sender address
     */
    public void saveDraft(EmailTicket ticket) {
        if (!settingsService.isDraftEnabled()) {
            log.debug("Gmail draft saving disabled (notification.draft-enabled=false)");
            return;
        }
        if (ticket.getMailboxId() == null || !secretCipher.isConfigured()) {
            log.debug("Ticket {} has no ingesting mailbox — no draft saved", ticket.getId());
            return;
        }
        Optional<TenantMailbox> found = mailboxes.findByIdAndEnabledTrue(ticket.getMailboxId());
        if (found.isEmpty()) {
            log.debug("Mailbox of ticket {} is gone or disabled — no draft saved", ticket.getId());
            return;
        }
        TenantMailbox mailbox = found.get();

        Store store = null;
        Folder draftsFolder = null;
        try {
            Session session = Session.getInstance(buildImapProperties(mailbox.getHost(), mailbox.getPort()));
            store = session.getStore("imaps");
            store.connect(mailbox.getHost(), mailbox.getPort(), mailbox.getUsername(),
                    secretCipher.decrypt(mailbox.getPasswordEnc()));

            draftsFolder = findDraftsFolder(store);
            if (draftsFolder == null) {
                log.warn("Drafts folder not found in IMAP for ticket {} — skipping Gmail draft", ticket.getId());
                return;
            }

            if (!draftsFolder.isOpen()) {
                draftsFolder.open(Folder.READ_WRITE);
            }

            draftsFolder.appendMessages(new Message[]{buildDraftMessage(session, ticket, mailbox.effectiveFromAddress())});
            draftSavedCounter.increment();
            log.info("Draft saved to Gmail Drafts for ticket {}", ticket.getId());

        } catch (Exception e) {
            draftFailedCounter.increment();
            log.warn("Failed to save draft to Gmail Drafts for ticket {}: {}", ticket.getId(), EmailAddressUtils.maskAddresses(e.getMessage()));
        } finally {
            try { if (draftsFolder != null && draftsFolder.isOpen()) draftsFolder.close(false); }
            catch (Exception e) { log.debug("Failed to close drafts folder: {}", e.getMessage()); }
            try { if (store != null) store.close(); }
            catch (Exception e) { log.debug("Failed to close IMAP store: {}", e.getMessage()); }
        }
    }

    Properties buildImapProperties(String host, int port) {
        Properties props = new Properties();
        props.put("mail.store.protocol",         "imaps");
        props.put("mail.imaps.host",              host);
        props.put("mail.imaps.port",              String.valueOf(port));
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout",           "10000");
        mailTlsPolicy.applyImaps(props, host);
        return props;
    }

    /**
     * Locates the Drafts folder: first tries common Gmail paths,
     * then searches by the \Drafts IMAP attribute (language-independent).
     */
    private Folder findDraftsFolder(Store store) {
        for (String name : new String[]{"[Gmail]/Drafts", "[Google Mail]/Drafts", "Drafts"}) {
            try {
                Folder f = store.getFolder(name);
                if (f.exists()) {
                    log.debug("Found Drafts folder: {}", name);
                    return f;
                }
            } catch (Exception e) {
                log.debug("Could not probe folder {}: {}", name, e.getMessage());
            }
        }

        try {
            for (Folder f : store.getDefaultFolder().list("*")) {
                if (f.getFullName().toLowerCase().contains("draft")) {
                    log.debug("Found Drafts folder by name match: {}", f.getFullName());
                    return f;
                }
            }
        } catch (Exception e) {
            log.warn("Could not list IMAP folders to find Drafts: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Builds a MimeMessage for the draft with the appropriate headers and body.
     */
    private MimeMessage buildDraftMessage(Session session, EmailTicket ticket, String from) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));

        String customerEmail = EmailAddressUtils.extract(ticket.getSenderEmail());
        if (!customerEmail.isBlank()) {
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(customerEmail));
        }

        String subject = ticket.getSubject() != null ? ticket.getSubject() : "";
        message.setSubject("Re: " + subject, "UTF-8");
        message.setText(ticket.getDraftReply() != null ? ticket.getDraftReply().trim() : "", "UTF-8", "plain");

        if (ticket.getMessageId() != null) {
            message.setHeader("In-Reply-To", ticket.getMessageId());
            message.setHeader("References",  ticket.getMessageId());
        }

        message.saveChanges();
        return message;
    }

}
