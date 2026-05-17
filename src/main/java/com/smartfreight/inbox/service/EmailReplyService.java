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
 * Operator email reply sending service.
 *
 * Used in the final step of the workflow:
 * 1. The operator reviews the AI-generated draft
 * 2. The operator clicks "Send" in the UI
 * 3. {@link #sendReply} sends the message via SMTP to the sender's address
 * 4. In-Reply-To/References headers are set for correct threading
 *
 * Dependencies: {@link com.smartfreight.inbox.config.SmtpConfig} (JavaMailSender bean)
 * and SMTP environment variables.
 */
@Service
public class EmailReplyService {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyService.class);

    private final JavaMailSender mailSender;

    /** Sender email address (from which operator replies are dispatched). */
    @Value("${smtp.username}")
    private String fromAddress;

    public EmailReplyService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends the operator's draft back to the original email sender.
     *
     * Algorithm:
     * 1. A MimeMessage is created for the HTML/text content
     * 2. In-Reply-To and References headers are set for threading (RFC 2822)
     * 3. A clean email address is extracted from senderEmail (handles "Name &lt;email&gt;" format)
     * 4. The message is sent via SMTP synchronously
     *
     * @param ticket ticket with message data: sender address, subject, draft
     * @throws RuntimeException if SMTP sending failed
     */
    public void sendReply(EmailTicket ticket) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            String to = extractEmail(ticket.getSenderEmail());
            if (to.isBlank() || !isValidEmail(to)) {
                throw new IllegalArgumentException(
                        "Cannot send reply: invalid or missing recipient address for ticket " + ticket.getId());
            }
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Re: " + (ticket.getSubject() != null ? ticket.getSubject() : ""));
            helper.setText(ticket.getDraftReply() != null ? ticket.getDraftReply() : "");

            if (ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References", ticket.getMessageId());
            }

            mailSender.send(message);
            log.info("Reply sent for ticket {} to {}", ticket.getId(), to);

        } catch (Exception e) {
            log.error("Failed to send reply for ticket {}: {}", ticket.getId(), e.getMessage());
            throw new RuntimeException("Failed to send email reply: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a clean email address from an RFC 5321 formatted string.
     *
     * Examples:
     * — {@code "john@example.com"} → {@code "john@example.com"}
     * — {@code "John Doe <john@example.com>"} → {@code "john@example.com"}
     * — {@code "john+tag@example.com"} → {@code "john+tag@example.com"}
     *
     * If parsing fails (invalid format), the raw string is returned as a fallback.
     *
     * @param raw RFC 5321 formatted string or a plain email address
     * @return clean email address or an empty string if null
     */
    private String extractEmail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            return raw;
        }
    }

    private boolean isValidEmail(String email) {
        try {
            new InternetAddress(email).validate();
            return email.contains("@");
        } catch (Exception e) {
            return false;
        }
    }
}
