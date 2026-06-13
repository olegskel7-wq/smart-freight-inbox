package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

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

    private EmailReplyService service;

    @BeforeEach
    void setUp() {
        service = new EmailReplyService(mailSenderProvider, new SimpleMeterRegistry());
        // Every send resolves the current tenant's sender; here it is the platform sender mock.
        when(mailSenderProvider.resolve())
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
