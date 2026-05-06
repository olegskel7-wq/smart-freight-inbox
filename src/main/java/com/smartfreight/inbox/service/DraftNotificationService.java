package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Pašto operatoriui pranešimų apie paruoštus juodraščius paslauga.
 *
 * Kai AI sugeneruoja juodraštį, ši paslauga išsiunčia pranešimą
 * operatoriaus pašto dėžutę. Laiške nurodomas Reply-To: kliento adresas,
 * todėl operatoriui užtenka peržiūrėti tekstą ir paspausti Reply –
 * atsakymas nukeliauja tiesiai klientui.
 *
 * Saugumo antraštė X-SmartFreight-Draft: true apsaugo nuo begalinio ciklo:
 * IMAP IDLE stebimas tas pats pašto dėžutė, ir be šios antraštės
 * pranešimai būtų apdorojami pakartotinai.
 *
 * Jei siuntimas nepavyko – klaida tik užregistruojama žurnale;
 * bilieto apdorojimas nesustabdomas (DRAFT_READY išsaugomas bet kuriuo atveju).
 */
@Service
public class DraftNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DraftNotificationService.class);

    /**
     * Custom header that marks system-generated notification emails.
     * EmailIngestionService skips any incoming message carrying this header
     * to prevent an infinite processing loop.
     */
    public static final String DRAFT_HEADER_NAME  = "X-SmartFreight-Draft";
    public static final String DRAFT_HEADER_VALUE = "true";

    private final JavaMailSender mailSender;

    @Value("${smtp.username}")
    private String operatorEmail;

    @Value("${notification.draft-enabled:true}")
    private boolean draftEnabled;

    public DraftNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Išsiunčia operatoriui pranešimą su AI sugeneruotu juodraščiu.
     *
     * Laiško struktūra:
     * – From / To: operatoriaus el. paštas (tas pats, nes pranešimas skirtas sau)
     * – Reply-To: pradinio laiško siuntėjas (klientas)
     * – Subject: "Re: <originali tema>" + In-Reply-To / References tinkamam sriegimui
     * – Body: juodraščio tekstas su trumpu kontekstiniu komentaru
     * – X-SmartFreight-Draft: true (apsauga nuo begalinio ciklo)
     *
     * @param ticket bilietas su sugeneruotu juodraščiu
     */
    public void notifyDraftReady(EmailTicket ticket) {
        if (!draftEnabled) {
            log.debug("Draft notifications disabled (notification.draft-enabled=false)");
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(operatorEmail);
            helper.setTo(operatorEmail);

            String customerEmail = extractEmail(ticket.getSenderEmail());
            if (!customerEmail.isBlank()) {
                message.setReplyTo(new InternetAddress[]{new InternetAddress(customerEmail)});
            }

            String subject = ticket.getSubject() != null ? ticket.getSubject() : "";
            helper.setSubject("Re: " + subject);

            helper.setText(buildBody(ticket, customerEmail));

            // Group this notification in the same Gmail thread as the original customer email
            if (ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References",  ticket.getMessageId());
            }

            // Prevents IMAP IDLE from re-processing this system message
            message.setHeader(DRAFT_HEADER_NAME, DRAFT_HEADER_VALUE);

            mailSender.send(message);
            log.info("Draft notification sent to operator for ticket {}", ticket.getId());

        } catch (Exception e) {
            // Non-fatal: ticket remains DRAFT_READY; operator can still use the web UI
            log.warn("Failed to send draft notification for ticket {}: {}", ticket.getId(), e.getMessage());
        }
    }

    /**
     * Formuoja laiško kūną su juodraščio tekstu ir kontekstine informacija.
     */
    private String buildBody(EmailTicket ticket, String customerEmail) {
        String draft = ticket.getDraftReply() != null ? ticket.getDraftReply().trim() : "";
        String divider = "─────────────────────────────────────────";

        return divider + "\n"
                + draft + "\n"
                + divider + "\n\n"
                + "Smart Freight AI draft — review and click Reply to send to: " + customerEmail + "\n"
                + "Ticket ID: " + ticket.getId();
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
