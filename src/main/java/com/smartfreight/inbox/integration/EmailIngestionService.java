package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.DraftNotificationService;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.TicketProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Incoming email ingestion service.
 *
 * Acts as the receiver of the IMAP IDLE channel (mailChannel) — Spring Integration
 * automatically invokes {@link #processIncomingEmail} when a new message arrives.
 *
 * Processing pipeline:
 * 1. The message is copied into memory (guard against FolderClosedException).
 * 2. The Message-ID header is checked — messages without it are ignored.
 * 3. The message body is extracted (plain text or multipart).
 * 4. LLM classification (Qwen) — BEFORE the first DB query, so the JDBC connection is not held.
 *    Unrelated emails (non-freight inquiries) are rejected here with no DB record created.
 * 5. Duplicate check by Message-ID — IMAP may deliver the same message twice.
 * 6. A ticket is created and persisted.
 * 7. After transaction commit (afterCommit) the AI pipeline is started.
 */
@Service
public class EmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestionService.class);

    /** Hard cap on email size — protects heap from runaway IMAP messages. */
    private static final int MAX_EMAIL_BYTES = 10 * 1024 * 1024;

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final EmailClassificationService emailClassificationService;
    private final Counter receivedCounter;

    public EmailIngestionService(EmailTicketRepository emailTicketRepository,
                                 TicketProcessingService ticketProcessingService,
                                 EmailClassificationService emailClassificationService,
                                 MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
        this.emailClassificationService = emailClassificationService;
        this.receivedCounter = Counter.builder("smartfreight_emails_received_total")
                .description("Total emails received and successfully ingested as tickets")
                .register(meterRegistry);
    }

    /**
     * Processes an incoming email: validates, persists a ticket, and starts the AI pipeline.
     *
     * @ServiceActivator — Spring Integration automatically invokes this method
     * when a new message arrives on the mailChannel channel.
     *
     * @Transactional — ticket persistence and AI startup are atomic.
     * If save() throws an exception, no ticket is created and processing does not begin.
     *
     * @param mimeMessage received Jakarta Mail message object
     */
    @ServiceActivator(inputChannel = "mailChannel")
    @Transactional
    public void processIncomingEmail(MimeMessage mimeMessage) {
        try {
            String[] draftFlag = mimeMessage.getHeader(DraftNotificationService.DRAFT_HEADER_NAME);
            if (draftFlag != null && draftFlag.length > 0
                    && DraftNotificationService.DRAFT_HEADER_VALUE.equalsIgnoreCase(draftFlag[0])) {
                log.debug("Skipping system-generated draft notification ({}={})",
                        DraftNotificationService.DRAFT_HEADER_NAME,
                        DraftNotificationService.DRAFT_HEADER_VALUE);
                return;
            }

            String[] originalMessageIds = mimeMessage.getHeader("Message-ID");
            if (originalMessageIds == null || originalMessageIds.length == 0) {
                log.warn("Incoming email has no Message-ID header, skipping");
                return;
            }
            String messageId = originalMessageIds[0];
            MDC.put("messageId", messageId);

            int reportedSize = mimeMessage.getSize();
            // getSize() returns -1 when the server does not report size — skip this pre-check in that case
            if (reportedSize > 0 && reportedSize > MAX_EMAIL_BYTES) {
                log.warn("Email too large ({} bytes reported by server), skipping to protect heap memory", reportedSize);
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mimeMessage.writeTo(baos);
            if (baos.size() > MAX_EMAIL_BYTES) {
                log.warn("Email serialized size {} bytes exceeds 10 MB limit, skipping", baos.size());
                return;
            }
            mimeMessage = new MimeMessage(null, new ByteArrayInputStream(baos.toByteArray()));

            String body = extractBody(mimeMessage);
            String subject = mimeMessage.getSubject() != null ? mimeMessage.getSubject() : "";

            String senderEmail = "";
            if (mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0) {
                senderEmail = mimeMessage.getFrom()[0].toString();
            }

            if (!emailClassificationService.isFreightInquiry(subject, body)) {
                log.info("Non-freight email ignored (no DB record created): subject='{}'", subject);
                return;
            }

            if (emailTicketRepository.findByMessageId(messageId).isPresent()) {
                log.info("Duplicate email ignored: {}", messageId);
                return;
            }

            EmailTicket ticket = EmailTicket.builder()
                    .messageId(messageId)
                    .subject(mimeMessage.getSubject())
                    .senderEmail(senderEmail)
                    .body(body)
                    .build();

            try {
                EmailTicket saved = emailTicketRepository.save(ticket);
                receivedCounter.increment();
                MDC.put("ticketId", String.valueOf(saved.getId()));
                log.info("Email ingested: ticket ID={}, subject='{}'", saved.getId(), saved.getSubject());

                final Long ticketId = saved.getId();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ticketProcessingService.process(ticketId);
                    }
                });

            } catch (DataIntegrityViolationException e) {
                log.info("Duplicate email on concurrent save, skipping: {}", messageId);
            }

        } catch (MessagingException | IOException e) {
            log.error("Failed to parse incoming email", e);
        } finally {
            MDC.remove("messageId");
            MDC.remove("ticketId");
        }
    }

    /**
     * Extracts the plain-text body from an email message.
     *
     * Handles three cases:
     * 1. Simple text message (String content)
     * 2. Multipart message — the first text/plain part is used
     * 3. Other — an empty string is returned
     *
     * @param message the email message object
     * @return extracted text or an empty string
     */
    private String extractBody(MimeMessage message) throws MessagingException, IOException {
        Object content = message.getContent();

        if (content instanceof String text) {
            return text;
        }

        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    sb.append(part.getContent());
                    sb.append("\n");
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
            if (multipart.getCount() > 0) {
                Object firstPart = multipart.getBodyPart(0).getContent();
                if (firstPart instanceof String s) {
                    return s;
                }
            }
        }

        log.warn("Could not extract text body from email, using empty string");
        return "";
    }
}
