package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.util.EmailAddressUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter replySentCounter;
    private final Counter replyFailedCounter;

    /** Sender email address (from which operator replies are dispatched). */
    private final String fromAddress;

    public EmailReplyService(JavaMailSender mailSender,
                             MeterRegistry meterRegistry,
                             @Value("${smtp.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.replySentCounter = Counter.builder("smartfreight_replies_total")
                .description("Operator reply send results")
                .tag("result", "sent")
                .register(meterRegistry);
        this.replyFailedCounter = Counter.builder("smartfreight_replies_total")
                .description("Operator reply send results")
                .tag("result", "failed")
                .register(meterRegistry);
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

            String to = EmailAddressUtils.extract(ticket.getSenderEmail());
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
            replySentCounter.increment();
            log.info("Reply sent for ticket {} to {}", ticket.getId(), to);

        } catch (Exception e) {
            replyFailedCounter.increment();
            log.error("Failed to send reply for ticket {}: {}", ticket.getId(), e.getMessage());
            throw new RuntimeException("Failed to send email reply: " + e.getMessage(), e);
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
