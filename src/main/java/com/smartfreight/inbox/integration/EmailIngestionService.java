package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.AttachmentParserService;
import com.smartfreight.inbox.service.DraftNotificationService;
import com.smartfreight.inbox.service.SettingsService;
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
 * 3. The message body is extracted (plain text or multipart); attachments are parsed
 *    locally (PDFBox/POI — CPU-bound, no network, so it stays on this thread).
 * 4. Duplicate check by Message-ID — IMAP may deliver the same message twice.
 * 5. A ticket is persisted with status RECEIVED, and a "ticket.created" event is written
 *    to the transactional outbox IN THE SAME transaction (Phase 1.3).
 * 6. OutboxRelay publishes the event to RabbitMQ; TicketEventConsumer classifies the email
 *    (Qwen) and runs the AI pipeline. A broker or Ollama outage therefore never blocks
 *    ingestion — events wait in the outbox, tickets wait in RECEIVED.
 *
 * Phase 1.3 session 3: NO LLM call happens on this thread anymore. Classification used to
 * run here (blocking IMAP IDLE for up to 30 s per email, losing mail when Ollama was down);
 * it now lives in the consumer, and non-freight emails get a persisted IRRELEVANT ticket
 * instead of vanishing without trace.
 */
@Service
public class EmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestionService.class);

    /** Hard cap on email size — protects heap from runaway IMAP messages. */
    private static final int MAX_EMAIL_BYTES = 10 * 1024 * 1024;

    /**
     * Feature flag for attachment parsing (Phase 1.1). Plan-based gating seam: today a global
     * {@code app_setting} row, per-tenant under multi-tenancy (Phase 2). Missing row = enabled.
     */
    private static final String FEATURE_ATTACHMENT_PARSING = "feature.attachment-parsing";

    private final EmailTicketRepository emailTicketRepository;
    private final TicketEventPublisher ticketEventPublisher;
    private final AttachmentParserService attachmentParserService;
    private final SettingsService settingsService;
    private final Counter receivedCounter;

    public EmailIngestionService(EmailTicketRepository emailTicketRepository,
                                 TicketEventPublisher ticketEventPublisher,
                                 AttachmentParserService attachmentParserService,
                                 SettingsService settingsService,
                                 MeterRegistry meterRegistry) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketEventPublisher = ticketEventPublisher;
        this.attachmentParserService = attachmentParserService;
        this.settingsService = settingsService;
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
     * @Transactional — ticket persistence and the outbox event are atomic.
     * If save() throws an exception, no ticket is created and no event is recorded.
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

            // Phase 1.1: pull text from PDF/Excel attachments. Persisted alongside the body so the
            // consumer-side classifier judges an RFQ whose body is just "please see attached" on the
            // attachment. Behind a feature flag (plan-gating seam) — disabled tenants skip parsing.
            String attachmentText = settingsService.isFeatureEnabled(FEATURE_ATTACHMENT_PARSING, true)
                    ? attachmentParserService.extractAttachmentText(mimeMessage)
                    : "";

            String senderEmail = "";
            if (mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0) {
                senderEmail = mimeMessage.getFrom()[0].toString();
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
                    .attachmentText(attachmentText.isBlank() ? null : attachmentText)
                    // RECEIVED = persisted but not yet classified; the consumer decides
                    // IRRELEVANT vs pipeline. NEW is reserved for the retry re-entry path.
                    .status(TicketStatus.RECEIVED)
                    .build();

            try {
                EmailTicket saved = emailTicketRepository.save(ticket);
                receivedCounter.increment();
                MDC.put("ticketId", String.valueOf(saved.getId()));
                log.info("Email ingested: ticket ID={}, subject='{}'", saved.getId(), saved.getSubject());

                // Same transaction as the ticket insert: either both become durable or neither.
                ticketEventPublisher.publishTicketCreated(saved.getId());

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
            StringBuilder plainSb = new StringBuilder();
            StringBuilder htmlSb  = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    plainSb.append(part.getContent()).append("\n");
                } else if (part.isMimeType("text/html")) {
                    htmlSb.append(part.getContent()).append("\n");
                }
            }
            if (!plainSb.isEmpty()) {
                return plainSb.toString();
            }
            // HTML-only email: strip tags for plain-text extraction
            if (!htmlSb.isEmpty()) {
                return htmlSb.toString().replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
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
