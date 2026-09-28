package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.messaging.TicketEventPublisher;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.AttachmentParserService;
import com.smartfreight.inbox.service.SettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Vienetų testai el. laiškų priėmimo paslaugai (EmailIngestionService).
 *
 * Tikrina:
 * – sėkmingą laiško priėmimą ir bilieto kūrimą
 * – dublikatų tikrinimą pagal Message-ID antraštę
 * – laiškų be Message-ID antraštės praleidimą
 * – paprasto teksto ir multipart laiškų turinio ištraukimą
 * – outbox įvykio („ticket.created") registravimą toje pačioje transakcijoje (Phase 1.3)
 *
 * Nuo Phase 1.3 AI konvejeris nebevyksta tiesiogiai: paslauga tik įrašo įvykį
 * per TicketEventPublisher (transactional outbox), o vartotojas (RabbitMQ consumer)
 * apdoroja bilietą asinchroniškai. Todėl testuose tikrinamas publisher kvietimas.
 *
 * Phase 1.3 session 3: classification moved off the IMAP thread into the consumer.
 * Ingestion persists every deduplicated email as RECEIVED with no LLM call at all —
 * EmailClassificationService is no longer a dependency of this service.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailIngestionServiceTest {

    /** The tenant_mailbox the adapter received the message on. */
    private static final Long MAILBOX_ID = 7L;

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketEventPublisher ticketEventPublisher;

    @Mock
    private SettingsService settingsService;

    @Mock
    private com.smartfreight.inbox.service.SlaService slaService;

    @Mock
    private com.smartfreight.inbox.webhook.WebhookEventPublisher webhookEventPublisher;

    private EmailIngestionService emailIngestionService;

    /** Jakarta Mail sesija be jokių savybių – naudojama MimeMessage kūrimui testams. */
    private Session mailSession;

    @BeforeEach
    void setUp() {
        emailIngestionService = new EmailIngestionService(
                emailTicketRepository, ticketEventPublisher,
                new AttachmentParserService(new SimpleMeterRegistry()), settingsService,
                slaService, webhookEventPublisher, new SimpleMeterRegistry());
        mailSession = Session.getInstance(new Properties());
        // Default: attachment-parsing feature on, so existing tests keep their behaviour.
        when(settingsService.isFeatureEnabled(any(), anyBoolean())).thenReturn(true);
    }

    /**
     * Sukuria paprastą tekstinį laišką su nurodytais parametrais.
     *
     * @param messageId   unikalus laiško identifikatorius (Message-ID antraštė)
     * @param from        siuntėjo el. pašto adresas
     * @param subject     laiško tema
     * @param textContent laiško tekstinis turinys
     * @return sukurtas MimeMessage objektas
     */
    private MimeMessage buildSimpleEmail(String messageId, String from,
                                         String subject, String textContent) throws Exception {
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(from));
        msg.setSubject(subject);
        msg.setText(textContent);
        msg.setHeader("Message-ID", messageId);
        return msg;
    }

    /**
     * Sukuria multipart laišką su teksto ir HTML dalimis.
     *
     * @param messageId   unikalus laiško identifikatorius
     * @param plainText   teksto/paprastas turinys (text/plain)
     * @param htmlContent HTML turinys (text/html)
     * @return sukurtas MimeMessage su dviem dalimis
     */
    private MimeMessage buildMultipartEmail(String messageId,
                                             String plainText, String htmlContent) throws Exception {
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setSubject("Multipart test");
        msg.setHeader("Message-ID", messageId);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plainText, "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlContent, "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(htmlPart);

        msg.setContent(multipart);
        return msg;
    }

    /**
     * Testas: naujas laiškas su unikaliu Message-ID išsaugomas kaip bilietas.
     *
     * Tikrinama:
     * – emailTicketRepository.save() iškviečiamas vieną kartą
     * – biliete teisingai užfiksuotas siuntėjas, tema, Message-ID
     */
    @Test
    void processIncomingEmail_newEmail_savesTicketWithCorrectFields() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<msg-001@example.com>",
                "customer@freight.com",
                "Container inquiry MSCU1234567",
                "Please check the status of container MSCU1234567.");

        when(emailTicketRepository.findByMessageId("<msg-001@example.com>"))
                .thenReturn(Optional.empty());

        EmailTicket savedTicket = EmailTicket.builder()
                .id(42L)
                .messageId("<msg-001@example.com>")
                .build();
        when(emailTicketRepository.save(any(EmailTicket.class))).thenReturn(savedTicket);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());

        EmailTicket captured = captor.getValue();
        assertThat(captured.getMessageId()).isEqualTo("<msg-001@example.com>");
        assertThat(captured.getSenderEmail()).contains("customer@freight.com");
        assertThat(captured.getSubject()).isEqualTo("Container inquiry MSCU1234567");
        assertThat(captured.getBody()).contains("MSCU1234567");
        // Phase 1.3 session 3: ingestion persists as RECEIVED — classification happens
        // later in the RabbitMQ consumer, never on the IMAP thread.
        assertThat(captured.getStatus()).isEqualTo(TicketStatus.RECEIVED);
        // Multi-inbox: the receiving mailbox is recorded so the reply leaves through it.
        assertThat(captured.getMailboxId()).isEqualTo(MAILBOX_ID);
    }

    /**
     * Phase 1.5: ingestion sets a provisional SLA deadline (GENERAL window — the type
     * is unknown until the consumer classifies). Off when feature.sla-timer is disabled.
     */
    @Test
    void processIncomingEmail_slaEnabled_setsProvisionalDeadline() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<sla-001@example.com>", "customer@freight.com", "Inquiry", "Where is my box?");
        when(emailTicketRepository.findByMessageId("<sla-001@example.com>")).thenReturn(Optional.empty());
        when(emailTicketRepository.save(any(EmailTicket.class)))
                .thenAnswer(inv -> inv.getArgument(0, EmailTicket.class));
        java.time.LocalDateTime deadline = java.time.LocalDateTime.now().plusHours(8);
        when(slaService.isEnabled()).thenReturn(true);
        when(slaService.deadlineFor(isNull(), any())).thenReturn(deadline);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getSlaDeadlineAt()).isEqualTo(deadline);
    }

    @Test
    void processIncomingEmail_slaDisabled_leavesDeadlineNull() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<sla-002@example.com>", "customer@freight.com", "Inquiry", "Where is my box?");
        when(emailTicketRepository.findByMessageId("<sla-002@example.com>")).thenReturn(Optional.empty());
        when(emailTicketRepository.save(any(EmailTicket.class)))
                .thenAnswer(inv -> inv.getArgument(0, EmailTicket.class));
        when(slaService.isEnabled()).thenReturn(false);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getSlaDeadlineAt()).isNull();
    }

    /**
     * Testas: laiškas su jau egzistuojančiu Message-ID praleidžiamas (dublikatas).
     *
     * IMAP protokolas gali pristatyti tą patį laišką du kartus po persijungimo –
     * dublikatų tikrinimas apsaugo nuo dvigubų biletų kūrimo.
     */
    @Test
    void processIncomingEmail_duplicateMessageId_skipsWithoutSaving() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<duplicate@example.com>",
                "customer@freight.com",
                "Duplicate email",
                "Already processed.");

        when(emailTicketRepository.findByMessageId("<duplicate@example.com>"))
                .thenReturn(Optional.of(EmailTicket.builder().id(1L).build()));

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(ticketEventPublisher);
    }

    /**
     * Testas: laiškas be Message-ID antraštės praleidžiamas.
     *
     * Message-ID yra privaloma antraštė deduplikacijai –
     * be jos negalime identifikuoti laiško unikaliai.
     */
    @Test
    void processIncomingEmail_noMessageIdHeader_skipsWithoutSaving() throws Exception {
        MimeMessage email = new MimeMessage(mailSession);
        email.setFrom(new InternetAddress("sender@example.com"));
        email.setSubject("No message ID");
        email.setText("Body text");
        // Message-ID antraštė specialiai nenustatoma

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        verify(emailTicketRepository, never()).save(any());
    }

    /**
     * Testas: multipart laiškas – ištraukiama text/plain dalis.
     *
     * Daugelis el. pašto klientų siunčia laiškus su text/plain ir text/html dalimis.
     * Paslauga turi ištraukti tik text/plain turinį logikos apdorojimui.
     */
    @Test
    void processIncomingEmail_multipartEmail_extractsPlainTextPart() throws Exception {
        MimeMessage email = buildMultipartEmail(
                "<multipart-001@example.com>",
                "Plain text with MSCU9876543",
                "<html><body>HTML with MSCU9876543</body></html>");

        when(emailTicketRepository.findByMessageId("<multipart-001@example.com>"))
                .thenReturn(Optional.empty());

        EmailTicket savedTicket = EmailTicket.builder().id(10L)
                .messageId("<multipart-001@example.com>").build();
        when(emailTicketRepository.save(any(EmailTicket.class))).thenReturn(savedTicket);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getBody()).contains("Plain text with MSCU9876543");
    }

    /**
     * Testas: po sėkmingo išsaugojimo registruojamas „ticket.created" outbox įvykis.
     *
     * Phase 1.3: vietoj tiesioginio process() kvietimo paslauga įrašo įvykį per
     * TicketEventPublisher TOJE PAČIOJE transakcijoje kaip ir bilietas — atominis
     * „bilietas + įvykis" įrašas yra transactional outbox šablono esmė.
     */
    @Test
    void processIncomingEmail_newEmail_publishesTicketCreatedEvent() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<outbox-001@example.com>",
                "test@example.com",
                "Test subject",
                "Test body");

        when(emailTicketRepository.findByMessageId(any())).thenReturn(Optional.empty());

        EmailTicket savedTicket = EmailTicket.builder().id(99L).tenantId(1L)
                .messageId("<outbox-001@example.com>").build();
        when(emailTicketRepository.save(any())).thenReturn(savedTicket);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        // The event carries the saved ticket's tenant (stamped by Hibernate @TenantId).
        verify(ticketEventPublisher).publishTicketCreated(99L, 1L);
    }

    /**
     * Test: even a non-freight-looking email is persisted as RECEIVED (Phase 1.3 session 3).
     *
     * Ingestion no longer calls the LLM classifier — relevance is decided by the
     * consumer, which parks junk mail as IRRELEVANT instead of dropping it untraced.
     */
    @Test
    void processIncomingEmail_anyEmail_persistedAsReceivedWithoutClassification() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<spam-001@example.com>",
                "marketing@somecompany.com",
                "Happy holidays!",
                "Wishing you all the best this holiday season.");

        when(emailTicketRepository.findByMessageId("<spam-001@example.com>"))
                .thenReturn(Optional.empty());
        EmailTicket savedTicket = EmailTicket.builder().id(7L).tenantId(1L)
                .messageId("<spam-001@example.com>").build();
        when(emailTicketRepository.save(any(EmailTicket.class))).thenReturn(savedTicket);

        emailIngestionService.processIncomingEmail(email, MAILBOX_ID);

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TicketStatus.RECEIVED);
        verify(ticketEventPublisher).publishTicketCreated(7L, 1L);
    }
}
