package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.util.EmailAddressUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Operator email reply sending service.
 *
 * Used in the final step of the workflow:
 * 1. The operator reviews the AI-generated draft
 * 2. The operator clicks "Send" in the UI
 * 3. {@link #sendReply} sends the message via SMTP to the sender's address
 * 4. In-Reply-To/References headers are set for correct threading
 *
 * The SMTP transport and FROM address are resolved per send by {@link MailSenderProvider},
 * from the mailbox the ticket arrived on (with tenant/platform fallbacks).
 */
@Service
public class EmailReplyService {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyService.class);

    private final MailSenderProvider mailSenderProvider;
    private final TenantDocumentRepository documentRepository;
    private final Counter replySentCounter;
    private final Counter replyFailedCounter;

    public EmailReplyService(MailSenderProvider mailSenderProvider,
                             TenantDocumentRepository documentRepository,
                             MeterRegistry meterRegistry) {
        this.mailSenderProvider = mailSenderProvider;
        this.documentRepository = documentRepository;
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
        MailSenderProvider.ResolvedSender resolved = mailSenderProvider.resolve(ticket.getMailboxId());
        JavaMailSender mailSender = resolved.sender();
        // Phase 2.7: a document-request reply may carry an attachment. Resolve it up front so the
        // helper is multipart ONLY when there is a file — text-only replies keep their exact prior
        // MIME shape. The lookup is RLS-scoped (a tenant context is active at every send site).
        Optional<TenantDocument> attachment = ticket.getAttachedDocumentId() == null
                ? Optional.empty()
                : documentRepository.findById(ticket.getAttachedDocumentId());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachment.isPresent(), "UTF-8");

            String to = EmailAddressUtils.extract(ticket.getSenderEmail());
            if (to.isBlank() || !isValidEmail(to)) {
                throw new IllegalArgumentException(
                        "Cannot send reply: invalid or missing recipient address for ticket " + ticket.getId());
            }
            helper.setFrom(resolved.from());
            helper.setTo(to);
            // Phase 2.4: a proactive status update is a fresh email, not a reply — its subject
            // stands on its own and its synthetic Message-ID belongs to no inbound thread, so no
            // "Re:" prefix and no threading headers. Inbound replies keep both.
            String subject = ticket.getSubject() != null ? ticket.getSubject() : "";
            helper.setSubject(ticket.isProactive() ? subject : "Re: " + subject);
            helper.setText(ticket.getDraftReply() != null ? ticket.getDraftReply() : "");

            if (attachment.isPresent()) {
                TenantDocument doc = attachment.get();
                helper.addAttachment(doc.getFilename(),
                        new ByteArrayResource(doc.getContent()),
                        doc.getContentType() != null ? doc.getContentType() : "application/octet-stream");
                log.info("Attaching document {} ({}) to reply for ticket {}",
                        doc.getId(), doc.getFilename(), ticket.getId());
            }

            if (!ticket.isProactive() && ticket.getMessageId() != null) {
                message.setHeader("In-Reply-To", ticket.getMessageId());
                message.setHeader("References", ticket.getMessageId());
            }

            mailSender.send(message);
            replySentCounter.increment();
            log.info("Reply sent for ticket {}", ticket.getId());

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
