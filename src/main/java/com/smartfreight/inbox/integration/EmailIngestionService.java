package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.TicketProcessingService;
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
 * Gaunamų el. laiškų priėmimo paslauga.
 *
 * Yra IMAP IDLE kanalo (mailChannel) gavėjas – Spring Integration
 * automatiškai iškviečia {@link #processIncomingEmail} gavus naują laišką.
 *
 * Vykdomas konvejeris:
 * 1. Laiškas nukopijuojamas į atmintį (apsauga nuo FolderClosedException).
 * 2. Tikrinama Message-ID antraštė – be jos laiškas ignoruojamas.
 * 3. Dublikatų tikrinimas pagal Message-ID – IMAP gali pristatyti tą patį laišką du kartus.
 * 4. Ištraukiamas laiško turinys (paprastas tekstas arba multipart).
 * 5. Sukuriamas ir išsaugomas bilietas.
 * 6. Po transakcijos įvykdymo (afterCommit) paleidžiamas AI konvejeris.
 */
@Service
public class EmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestionService.class);

    private final EmailTicketRepository emailTicketRepository;
    private final TicketProcessingService ticketProcessingService;

    public EmailIngestionService(EmailTicketRepository emailTicketRepository,
                                 TicketProcessingService ticketProcessingService) {
        this.emailTicketRepository = emailTicketRepository;
        this.ticketProcessingService = ticketProcessingService;
    }

    /**
     * Apdoroja gautą el. laišką: tikrina, išsaugo biletą, paleidžia AI konvejerį.
     *
     * @ServiceActivator – Spring Integration automatiškai iškviečia šį metodą
     * gavus naują laišką mailChannel kanale.
     *
     * @Transactional – bilieto išsaugojimas ir AI paleidimas yra atomiškas.
     * Jei save() meta išimtį – bilietas nesukuriamas ir apdorojimas nepradedamas.
     *
     * @param mimeMessage gautas Jakarta Mail laiško objektas
     */
    @ServiceActivator(inputChannel = "mailChannel")
    @Transactional
    public void processIncomingEmail(MimeMessage mimeMessage) {
        try {
            // Iš karto nukopijuojame visą laišką į atmintį – IMAP pristato MimeMessage
            // kaip nuorodą į serverį, ir vėliau bandant skaityti turinį ryšys jau uždarytas
            // (FolderClosedException). 10 MB limitas apsaugo heap atmintį.
            final int MAX_EMAIL_BYTES = 10 * 1024 * 1024;
            int reportedSize = mimeMessage.getSize();
            if (reportedSize > MAX_EMAIL_BYTES) {
                log.warn("Email too large ({} bytes), skipping to protect heap memory", reportedSize);
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mimeMessage.writeTo(baos);
            if (baos.size() > MAX_EMAIL_BYTES) {
                log.warn("Email serialized size {} bytes exceeds 10 MB limit, skipping", baos.size());
                return;
            }
            mimeMessage = new MimeMessage(null, new ByteArrayInputStream(baos.toByteArray()));

            // RFC 2822 Message-ID antraštė – privaloma unikaliam identifikavimui
            String[] messageIds = mimeMessage.getHeader("Message-ID");
            if (messageIds == null || messageIds.length == 0) {
                log.warn("Incoming email has no Message-ID header, skipping");
                return;
            }

            String messageId = messageIds[0];
            MDC.put("messageId", messageId);

            // Dublikatų tikrinimas: IMAP gali pristatyti tą patį laišką du kartus
            // po persijungimo – unikalus indeksas DB tai apsaugo, bet geriau aptikti anksčiau
            if (emailTicketRepository.findByMessageId(messageId).isPresent()) {
                log.info("Duplicate email ignored: {}", messageId);
                return;
            }

            String body = extractBody(mimeMessage);

            String senderEmail = "";
            if (mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0) {
                senderEmail = mimeMessage.getFrom()[0].toString();
            }

            // Builder numatytosios reikšmės: status=NEW, retryCount=0
            EmailTicket ticket = EmailTicket.builder()
                    .messageId(messageId)
                    .subject(mimeMessage.getSubject())
                    .senderEmail(senderEmail)
                    .body(body)
                    .build();

            try {
                EmailTicket saved = emailTicketRepository.save(ticket);
                MDC.put("ticketId", String.valueOf(saved.getId()));
                log.info("Email ingested: ticket ID={}, subject='{}'", saved.getId(), saved.getSubject());

                // AI konvejeris paleidžiamas TIK PO transakcijos įvykdymo (afterCommit):
                // process() turi matyti išsaugotą biletą DB savo transakcijoje.
                // Laikant DB ryšį atvirą Ollama laukimo metu (150 s) blokuotų HikariCP baseiną.
                final Long ticketId = saved.getId();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ticketProcessingService.process(ticketId);
                    }
                });

            } catch (DataIntegrityViolationException e) {
                // Lenktynės: du srautai vienu metu praėjo findByMessageId tikrinimą
                // ir abu bandė išsaugoti. Unikalus indeksas atmetė antrą – tai normalu.
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
     * Ištraukia tekstinį laiško turinį.
     *
     * Apdoroja tris atvejus:
     * 1. Paprastas tekstinis laiškas (String turinys)
     * 2. Multipart laiškas – imama pirmoji text/plain dalis
     * 3. Kita – grąžinama tuščia eilutė
     *
     * @param message laiško objektas
     * @return ištrauktas tekstas arba tuščia eilutė
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
                // Imame tik text/plain dalis – HTML ir priedai ignoruojami
                if (part.isMimeType("text/plain")) {
                    sb.append(part.getContent());
                    sb.append("\n");
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
            // Jei text/plain nerasta – bandome imti bent pirmą dalį
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
