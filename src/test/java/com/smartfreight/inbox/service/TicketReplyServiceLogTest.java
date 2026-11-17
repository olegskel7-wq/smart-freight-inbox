package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.webhook.WebhookEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** INFO+ logs carry ids, never customer content: a failed send logs the SMTP error with the address masked. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TicketReplyServiceLogTest {

    @Mock private EmailTicketRepository emailTicketRepository;
    @Mock private EmailReplyService emailReplyService;
    @Mock private TicketAuditService auditService;
    @Mock private SendRateLimiter sendRateLimiter;
    @Mock private QuoteFollowUpService quoteFollowUpService;
    @Mock private DocumentService documentService;
    @Mock private FeedbackLoopService feedbackLoopService;
    @Mock private WebhookEventPublisher webhookEventPublisher;
    @Mock private SettingsService settingsService;

    @Test
    void failedSend_logsTheSmtpErrorWithTheRecipientMasked(CapturedOutput output) {
        TicketReplyService service = new TicketReplyService(emailTicketRepository, emailReplyService, auditService,
                sendRateLimiter, quoteFollowUpService, documentService, feedbackLoopService, webhookEventPublisher,
                new SlaService(settingsService, new SlaProperties(), new SimpleMeterRegistry()));
        EmailTicket ticket = EmailTicket.builder().id(7L).messageId("<m7@x>").senderEmail("sue@acme.test")
                .subject("s").status(TicketStatus.DRAFT_READY).build();
        when(sendRateLimiter.tryAcquire(anyLong())).thenReturn(true);
        doThrow(new MailSendException("550 5.1.1 <sue@acme.test>: mailbox unavailable"))
                .when(emailReplyService).sendReply(any(), any());

        TicketReplyService.DraftOutcome outcome = service.applyDraft(ticket, "send", "Reply", "Op");

        assertThat(outcome).isEqualTo(TicketReplyService.DraftOutcome.SEND_FAILED);
        assertThat(output.getAll())
                .contains("Failed to send reply for ticket 7 (API): MailSendException: 550 5.1.1 <***@acme.test>")
                .doesNotContain("sue@acme.test");
    }
}
