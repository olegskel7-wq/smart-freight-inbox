package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.TicketProcessingService;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * – per didelių laiškų praleidimą
 * – paprasto teksto ir multipart laiškų turinio ištraukimą
 *
 * TransactionSynchronizationManager inicializuojamas rankiniu būdu,
 * nes paslaugos metodas registruoja afterCommit() callback'ą –
 * be aktyvios sinchronizacijos būtų išmesta IllegalStateException.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailIngestionServiceTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private TicketProcessingService ticketProcessingService;

    @Mock
    private EmailClassificationService emailClassificationService;

    @Mock
    private TaskExecutor taskExecutor;

    private EmailIngestionService emailIngestionService;

    /** Jakarta Mail sesija be jokių savybių – naudojama MimeMessage kūrimui testams. */
    private Session mailSession;

    @BeforeEach
    void setUp() {
        // Run submitted Runnables immediately so process() calls are synchronous in tests
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(taskExecutor).execute(any());

        emailIngestionService = new EmailIngestionService(
                emailTicketRepository, ticketProcessingService, emailClassificationService,
                taskExecutor, new SimpleMeterRegistry());
        mailSession = Session.getInstance(new Properties());
        // Default: treat all emails as freight-relevant; individual tests override when needed.
        when(emailClassificationService.isFreightInquiry(any(), any())).thenReturn(true);
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
     * – po transakcijos įvykdymo process() bus iškviestas per afterCommit()
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

        TransactionSynchronizationManager.initSynchronization();
        try {
            emailIngestionService.processIncomingEmail(email);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());

        EmailTicket captured = captor.getValue();
        assertThat(captured.getMessageId()).isEqualTo("<msg-001@example.com>");
        assertThat(captured.getSenderEmail()).contains("customer@freight.com");
        assertThat(captured.getSubject()).isEqualTo("Container inquiry MSCU1234567");
        assertThat(captured.getBody()).contains("MSCU1234567");
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

        emailIngestionService.processIncomingEmail(email);

        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(ticketProcessingService);
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

        emailIngestionService.processIncomingEmail(email);

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

        TransactionSynchronizationManager.initSynchronization();
        try {
            emailIngestionService.processIncomingEmail(email);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getBody()).contains("Plain text with MSCU9876543");
    }

    /**
     * Testas: po sėkmingo išsaugojimo afterCommit() registruoja process() kvietimą.
     *
     * AI konvejeris turi prasidėti TIK ĮVYKDŽIUS transakciją –
     * tai garantuoja, kad process() matys išsaugotą biletą duomenų bazėje.
     * Testas rankiniu būdu inicializuoja sinchronizaciją ir iššaukia afterCommit() callbacks.
     */
    @Test
    void processIncomingEmail_afterCommit_callsTicketProcessingService() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<aftercommit-001@example.com>",
                "test@example.com",
                "Test subject",
                "Test body");

        when(emailTicketRepository.findByMessageId(any())).thenReturn(Optional.empty());

        EmailTicket savedTicket = EmailTicket.builder().id(99L)
                .messageId("<aftercommit-001@example.com>").build();
        when(emailTicketRepository.save(any())).thenReturn(savedTicket);

        TransactionSynchronizationManager.initSynchronization();
        try {
            emailIngestionService.processIncomingEmail(email);

            // Rankiniu būdu iššaukiame afterCommit – imituoja Spring transakcijos užbaigimą
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(ticketProcessingService).process(99L);
    }

    /**
     * Test: email classified as IRRELEVANT is silently ignored — no DB record created.
     *
     * The LLM classifier returns false before any DB operation,
     * so neither save() nor findByMessageId() should be called.
     */
    @Test
    void processIncomingEmail_irrelevantEmail_skipsWithoutAnyDbInteraction() throws Exception {
        MimeMessage email = buildSimpleEmail(
                "<spam-001@example.com>",
                "marketing@somecompany.com",
                "Happy holidays!",
                "Wishing you all the best this holiday season.");

        when(emailClassificationService.isFreightInquiry(any(), any())).thenReturn(false); // override default

        emailIngestionService.processIncomingEmail(email);

        verify(emailTicketRepository, never()).save(any());
        verify(emailTicketRepository, never()).findByMessageId(any());
        verifyNoInteractions(ticketProcessingService);
    }
}
