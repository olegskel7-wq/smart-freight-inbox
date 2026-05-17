package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.util.EmailAddressUtils;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Notification service that emails the operator when a draft is ready.
 *
 * When the AI generates a draft, this service sends a notification to
 * the operator's mailbox. The message sets Reply-To to the customer's address,
 * so the operator only needs to review the text and click Reply —
 * the reply goes directly to the customer.
 *
 * The security header X-SmartFreight-Draft: true protects against an infinite loop:
 * the same mailbox is monitored by IMAP IDLE, and without this header
 * the notifications would be processed repeatedly.
 *
 * If sending fails, the error is only logged;
 * ticket processing is not interrupted (DRAFT_READY is saved regardless).
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
    private final SettingsService settingsService;
    private final String operatorEmail;

    public DraftNotificationService(JavaMailSender mailSender,
                                    SettingsService settingsService,
                                    @Value("${smtp.username}") String operatorEmail) {
        this.mailSender = mailSender;
        this.settingsService = settingsService;
        this.operatorEmail = operatorEmail;
    }

    /**
     * Sends the operator a notification with the AI-generated draft.
     *
     * Message structure:
     * — From / To: operator's email (same address, as the notification is to self)
     * — Reply-To: original email sender (the customer)
     * — Subject: "Re: &lt;original subject&gt;" + In-Reply-To / References for proper threading
     * — Body: draft text with a short contextual note
     * — X-SmartFreight-Draft: true (protection against the infinite loop)
     *
     * @param ticket ticket with the generated draft
     */
    public void notifyDraftReady(EmailTicket ticket) {
        if (!settingsService.isDraftEnabled()) {
            log.debug("Draft notifications disabled (notification.draft-enabled=false)");
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(operatorEmail);
            helper.setTo(operatorEmail);

            String customerEmail = EmailAddressUtils.extract(ticket.getSenderEmail());
            if (!customerEmail.isBlank()) {
                message.setReplyTo(new InternetAddress[]{new InternetAddress(customerEmail)});
            }

            String subject = ticket.getSubject() != null ? ticket.getSubject() : "";
            helper.setSubject("Re: " + subject);

            helper.setText(buildBody(ticket, customerEmail));

            if (ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References",  ticket.getMessageId());
            }

            message.setHeader(DRAFT_HEADER_NAME, DRAFT_HEADER_VALUE);

            mailSender.send(message);
            log.info("Draft notification sent to operator for ticket {}", ticket.getId());

        } catch (Exception e) {
            log.warn("Failed to send draft notification for ticket {}: {}", ticket.getId(), e.getMessage());
        }
    }

    /**
     * Builds the email body with the draft text and contextual information.
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

}
