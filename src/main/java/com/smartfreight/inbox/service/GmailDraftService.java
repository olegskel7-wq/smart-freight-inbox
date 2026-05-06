package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
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
 * Gmail „Drafts" aplanko paslauga – išsaugo AI sugeneruotą juodraštį
 * tiesiai į operatoriaus pašto dėžutės juodraščių aplanką per IMAP APPEND.
 *
 * Operatorius atidaro Gmail → Drafts → mato paruoštą laišką su:
 *  - To: kliento adresas
 *  - Subject: Re: <originali tema>
 *  - Body: AI sugeneruotas tekstas (redaguojamas prieš siuntimą)
 *  - Threading: In-Reply-To / References → laiškas grupuojamas į originalų pokalbi
 *
 * Naudoja tuos pačius IMAP duomenis kaip ImapConfig (spring.mail.*).
 * Jungtis atidaroma tik juodraščio išsaugojimui ir tuoj pat uždaroma.
 * Klaidos tik įrašomos į žurnalą – bilietas lieka DRAFT_READY bet kuriuo atveju.
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

    @Value("${notification.draft-enabled:true}")
    private boolean draftEnabled;

    /**
     * Išsaugo juodraštį į Gmail Drafts aplanką per IMAP APPEND.
     *
     * @param ticket bilietas su sugeneruotu juodraščiu ir siuntėjo adresu
     */
    public void saveDraft(EmailTicket ticket) {
        if (!draftEnabled) {
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
            log.info("Draft saved to Gmail Drafts for ticket {}, addressed to {}",
                    ticket.getId(), extractEmail(ticket.getSenderEmail()));

        } catch (Exception e) {
            log.warn("Failed to save draft to Gmail Drafts for ticket {}: {}", ticket.getId(), e.getMessage());
        } finally {
            try { if (draftsFolder != null && draftsFolder.isOpen()) draftsFolder.close(false); }
            catch (Exception ignored) {}
            try { if (store != null) store.close(); }
            catch (Exception ignored) {}
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
     * Ieško Drafts aplanko: pirma bando tipinius Gmail kelius,
     * tada ieško pagal \Drafts IMAP atributą (nepriklausomai nuo kalbos).
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

        // Language-independent fallback: find any folder whose name contains "draft"
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
     * Sukuria MimeMessage juodraščiui su tinkamais antraštėmis ir kūnu.
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

        // Thread this draft into the original customer email conversation
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
