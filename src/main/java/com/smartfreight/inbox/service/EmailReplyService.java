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

@Service
public class EmailReplyService {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyService.class);

    private final JavaMailSender mailSender;

    @Value("${smtp.username}")
    private String fromAddress;

    public EmailReplyService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // Sends the draft reply to the original sender.
    // Sets In-Reply-To / References headers so mail clients thread it correctly.
    public void sendReply(EmailTicket ticket) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            String to = extractEmail(ticket.getSenderEmail());
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

    // Parses "Name <email@example.com>" → "email@example.com"
    private String extractEmail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            return raw;
        }
    }
}
