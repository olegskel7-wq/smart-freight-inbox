package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * Uses the same IMAP credentials as ImapConfig (spring.mail.*).
 * The connection is opened only to save the draft and immediately closed.
 * Errors are only logged — the ticket remains DRAFT_READY regardless.
 */
@Service
public class GmailDraftService {

    private static final Logger log = LoggerFactory.getLogger(GmailDraftService.class);

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port:993}")
    private int port;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    private final SettingsService settingsService;
    private final Counter draftSavedCounter;
    private final Counter draftFailedCounter;

    public GmailDraftService(SettingsService settingsService, MeterRegistry meterRegistry) {
        this.settingsService = settingsService;
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
     * Saves a draft to the Gmail Drafts folder via IMAP APPEND.
     *
     * @param ticket ticket with the generated draft and sender address
     */
    public void saveDraft(EmailTicket ticket) {
        if (!settingsService.isDraftEnabled()) {
            log.debug("Gmail draft saving disabled (notification.draft-enabled=false)");
            return;
        }

        Store store = null;
        Folder draftsFolder = null;
        try {
            Session session = Session.getInstance(buildImapProperties());
            store = session.getStore("imaps");
            store.connect(host, port, username, password);

            draftsFolder = findDraftsFolder(store);
            if (draftsFolder == null) {
                log.warn("Drafts folder not found in IMAP for ticket {} — skipping Gmail draft", ticket.getId());
                return;
            }

            if (!draftsFolder.isOpen()) {
                draftsFolder.open(Folder.READ_WRITE);
            }

            draftsFolder.appendMessages(new Message[]{buildDraftMessage(session, ticket)});
            draftSavedCounter.increment();
            log.info("Draft saved to Gmail Drafts for ticket {}, addressed to {}",
                    ticket.getId(), extractEmail(ticket.getSenderEmail()));

        } catch (Exception e) {
            draftFailedCounter.increment();
            log.warn("Failed to save draft to Gmail Drafts for ticket {}: {}", ticket.getId(), e.getMessage());
        } finally {
            try { if (draftsFolder != null && draftsFolder.isOpen()) draftsFolder.close(false); }
            catch (Exception ignored) {}
            try { if (store != null) store.close(); }
            catch (Exception ignored) {}
        }
    }

    private Properties buildImapProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol",         "imaps");
        props.put("mail.imaps.host",              host);
        props.put("mail.imaps.port",              String.valueOf(port));
        props.put("mail.imaps.ssl.enable",        "true");
        props.put("mail.imaps.ssl.trust",         host);
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout",           "10000");
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
            } catch (Exception ignored) {}
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
    private MimeMessage buildDraftMessage(Session session, EmailTicket ticket) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(username));

        String customerEmail = extractEmail(ticket.getSenderEmail());
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

    private String extractEmail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            return raw;
        }
    }
}
