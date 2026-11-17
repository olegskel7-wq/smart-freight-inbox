package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.TenantDocumentRepository;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailReplyServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MailSenderProvider mailSenderProvider;

    @Mock
    private MimeMessage mimeMessage;

    @Mock
    private TenantDocumentRepository documentRepository;

    @Mock
    private TenantRepository tenantRepository;

    private EmailReplyService service;

    @BeforeEach
    void setUp() {
        service = new EmailReplyService(mailSenderProvider, documentRepository,
                new ReplySignatureService(tenantRepository), new SimpleMeterRegistry());
        // Every send resolves the sender for the ticket's mailbox; here it is the platform sender mock.
        when(mailSenderProvider.resolve(any()))
                .thenReturn(new MailSenderProvider.ResolvedSender(mailSender, "operator@smartfreight.com"));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    private EmailTicket buildTicket(String senderEmail, String subject, String draft, String messageId) {
        EmailTicket t = new EmailTicket();
        t.setId(42L);
        t.setSenderEmail(senderEmail);
        t.setSubject(subject);
        t.setDraftReply(draft);
        t.setMessageId(messageId);
        t.setStatus(TicketStatus.DRAFT_READY);
        return t;
    }

    // Сценарий 1: нормальная отправка — mailSender.send() вызван ровно один раз
    @Test
    void sendReply_validTicket_callsMailSenderOnce() throws Exception {
        EmailTicket ticket = buildTicket(
                "John Doe <john@example.com>",
                "Container status",
                "Dear John, your container is ready.",
                "<msg-001@gmail.com>"
        );

        service.sendReply(ticket);

        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Multi-inbox: the sender is resolved for the mailbox the ticket arrived on.
    @Test
    void sendReply_resolvesSenderForTicketsMailbox() {
        EmailTicket ticket = buildTicket("customer@example.com", "Quote", "Here is our quote.", "<msg-q@x>");
        ticket.setMailboxId(5L);

        service.sendReply(ticket);

        verify(mailSenderProvider).resolve(5L);
    }

    // Сценарий 2: In-Reply-To и References заголовки выставляются по messageId тикета
    @Test
    void sendReply_withMessageId_setsThreadingHeaders() throws Exception {
        EmailTicket ticket = buildTicket(
                "customer@example.com",
                "Urgent query",
                "We'll get back to you shortly.",
                "<original-msg-001@mail.example.com>"
        );

        service.sendReply(ticket);

        verify(mimeMessage).setHeader("In-Reply-To", "<original-msg-001@mail.example.com>");
        verify(mimeMessage).setHeader("References", "<original-msg-001@mail.example.com>");
    }

    // Сценарий 3: messageId == null — заголовки NOT выставляются, отправка всё равно происходит
    @Test
    void sendReply_noMessageId_skipsHeadersButStillSends() throws Exception {
        EmailTicket ticket = buildTicket("customer@example.com", "Query", "Draft text", null);

        service.sendReply(ticket);

        verify(mimeMessage, never()).setHeader(eq("In-Reply-To"), any());
        verify(mimeMessage, never()).setHeader(eq("References"), any());
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Сценарий 4: senderEmail в формате "Name <email>" — извлекается только адрес
    @Test
    void sendReply_rfcFormattedSender_extractsPureEmailAddress() throws Exception {
        // Capture что реально ушло в helper.setTo — мы проверяем через поведение mimeMessage
        // Главное: метод не выбрасывает исключение при RFC 5321 формате
        EmailTicket ticket = buildTicket(
                "Freight Manager <manager@freight.com>",
                "Status update",
                "Your container arrived.",
                "<msg@freight.com>"
        );

        assertDoesNotThrow(() -> service.sendReply(ticket));
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Сценарий 5: draftReply == null — отправляется пустой текст, исключения нет
    @Test
    void sendReply_nullDraftReply_sendsEmptyBody() throws Exception {
        EmailTicket ticket = buildTicket("client@example.com", "Query", null, null);

        assertDoesNotThrow(() -> service.sendReply(ticket));
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Сценарий 6: subject == null — тема становится "Re: " без NPE
    @Test
    void sendReply_nullSubject_usesEmptySubject() throws Exception {
        EmailTicket ticket = buildTicket("client@example.com", null, "Some draft", null);

        assertDoesNotThrow(() -> service.sendReply(ticket));
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Подпись добавляется только в исходящее письмо: подпись модели заменяется на имя оператора +
    // название тенанта, а draft_reply не трогается (KPI acceptance сравнивает неподписанный текст).
    @Test
    void sendReply_signsOutgoingTextOnly_draftReplyUnchanged() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("Felixstowe Freight Solutions Ltd");
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        String draft = "Your container has been released.\n\nBest regards,\n[Your Name]";
        EmailTicket ticket = buildTicket("client@example.com", "Release", draft, null);

        TenantContext.runAs(1L, () -> service.sendReply(ticket, "Jane Doe"));

        verify(mimeMessage).setText("Your container has been released.\n\n"
                + "Kind regards,\nJane Doe\nFelixstowe Freight Solutions Ltd", "UTF-8");
        assertEquals(draft, ticket.getDraftReply());
    }

    // Phase 2.4: проактивный апдейт — свежее письмо, НЕ reply. Даже при непустом (синтетическом)
    // messageId threading-заголовки не выставляются (новый тред), отправка происходит.
    @Test
    void sendReply_proactiveTicket_skipsThreadingHeaders() throws Exception {
        EmailTicket ticket = buildTicket(
                "customer@example.com",
                "Shipment update: MSCU1234567",
                "Good news — your shipment has been released.",
                "<proactive-7f3a@smartfreight>"
        );
        ticket.setProactive(true);

        service.sendReply(ticket);

        verify(mimeMessage, never()).setHeader(eq("In-Reply-To"), any());
        verify(mimeMessage, never()).setHeader(eq("References"), any());
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Phase 2.7: ticket carries an attached document → the stored file is loaded and the message
    // is built (multipart) without error; the lookup is keyed by the ticket's attachedDocumentId.
    @Test
    void sendReply_withAttachedDocument_loadsDocumentAndSends() throws Exception {
        EmailTicket ticket = buildTicket("client@example.com", "POD request",
                "Please find the document attached.", "<msg-doc@example.com>");
        ticket.setAttachedDocumentId(7L);
        TenantDocument doc = new TenantDocument();
        doc.setId(7L);
        doc.setFilename("pod.pdf");
        doc.setContentType("application/pdf");
        doc.setDocType(DocumentType.POD);
        doc.setContent(new byte[]{1, 2, 3});
        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));

        assertDoesNotThrow(() -> service.sendReply(ticket));

        verify(documentRepository).findById(7L);
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // No attachment id → the document store is never touched (text-only reply path unchanged).
    @Test
    void sendReply_noAttachment_doesNotTouchDocumentStore() throws Exception {
        EmailTicket ticket = buildTicket("client@example.com", "Query", "Plain reply", null);

        service.sendReply(ticket);

        verify(documentRepository, never()).findById(any());
        verify(mailSender, times(1)).send(mimeMessage);
    }

    // Сценарий 7: mailSender.send() бросает исключение — сервис оборачивает в RuntimeException
    @Test
    void sendReply_smtpFailure_throwsRuntimeException() {
        EmailTicket ticket = buildTicket("client@example.com", "Test", "Draft", null);
        doThrow(new org.springframework.mail.MailSendException("SMTP connection refused"))
                .when(mailSender).send(any(MimeMessage.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.sendReply(ticket));
        assertTrue(ex.getMessage().contains("Failed to send email reply"));
    }
}
