package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.QuoteFollowUp;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.QuoteFollowUpRepository;
import com.smartfreight.inbox.service.AiDraftingService;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.SettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuoteFollowUpScheduler} — the proactive quote-nudge poller (Phase 2.5).
 *
 * <p>Same shape as {@code StatusWatchSchedulerTest}: the static {@code TenantContext} helpers run
 * inline and the mocked {@link TransactionTemplate} invokes the callback, so the scheduler's
 * branching (not-due, replied/satisfied, due→nudge, single-shot deactivation, flag gate) is
 * exercised directly without a database.
 */
@ExtendWith(MockitoExtension.class)
class QuoteFollowUpSchedulerTest {

    @Mock private QuoteFollowUpRepository followUpRepository;
    @Mock private EmailTicketRepository emailTicketRepository;
    @Mock private AiDraftingService aiDraftingService;
    @Mock private AutoSendService autoSendService;
    @Mock private SettingsService settingsService;
    @Mock private TransactionTemplate transactionTemplate;

    private QuoteFollowUpScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().when(settingsService.isFeatureEnabled(eq(QuoteFollowUpScheduler.FEATURE_KEY), eq(false)))
                .thenReturn(true);
        lenient().when(settingsService.getString(eq(QuoteFollowUpScheduler.KEY_DELAY_HOURS), anyString()))
                .thenReturn(String.valueOf(QuoteFollowUpScheduler.DEFAULT_DELAY_HOURS));
        // By default the customer has stayed silent; the satisfied test overrides this.
        lenient().when(emailTicketRepository.existsInboundReplyFrom(anyString(), any())).thenReturn(false);
        lenient().when(emailTicketRepository.save(any(EmailTicket.class))).thenAnswer(inv -> {
            EmailTicket t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });
        scheduler = new QuoteFollowUpScheduler(followUpRepository, emailTicketRepository,
                aiDraftingService, autoSendService, settingsService, transactionTemplate,
                new SimpleMeterRegistry());
    }

    private QuoteFollowUp followUp(Long id, LocalDateTime quoteSentAt) {
        QuoteFollowUp f = new QuoteFollowUp();
        f.setId(id);
        f.setTenantId(1L);
        f.setTicketId(42L);
        f.setCustomerEmail("customer@acme.com");
        f.setQuoteSentAt(quoteSentAt);
        f.setActive(true);
        return f;
    }

    private EmailTicket originalRfq() {
        EmailTicket t = new EmailTicket();
        t.setId(42L);
        t.setSubject("Quote for 2x40ft");
        t.setBody("Please quote Felixstowe to Leeds");
        t.setExtractedIdentifier(null);
        t.setMailboxId(3L); // the inbox the RFQ arrived on
        return t;
    }

    private void haveOneFollowUp(QuoteFollowUp f) {
        when(followUpRepository.findByActiveTrue()).thenReturn(List.of(f));
        when(followUpRepository.findById(f.getId())).thenReturn(Optional.of(f));
    }

    @Test
    void due_andSilent_leftAsDraft_whenAutoSendDeclines() {
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(50)); // past the 48h delay
        haveOneFollowUp(f);
        when(emailTicketRepository.findById(42L)).thenReturn(Optional.of(originalRfq()));
        when(aiDraftingService.generateQuoteFollowUp(any())).thenReturn("Just following up on our quote.");
        when(autoSendService.shouldAutoSend(any())).thenReturn(false);

        scheduler.pollFollowUps();

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        EmailTicket ticket = captor.getValue();
        assertTrue(ticket.isProactive());
        assertEquals(TicketType.RFQ, ticket.getTicketType());
        assertEquals(TicketStatus.DRAFT_READY, ticket.getStatus());
        assertEquals("customer@acme.com", ticket.getSenderEmail());
        assertEquals("Just following up on our quote.", ticket.getDraftReply());
        assertTrue(ticket.getSubject().startsWith("Following up:"));
        assertEquals(3L, ticket.getMailboxId(), "nudge leaves through the RFQ's inbox");

        assertFalse(f.isActive(), "a single nudge ends the subscription");
        verify(followUpRepository).save(f);
        verify(autoSendService, never()).autoSend(anyLong());
    }

    @Test
    void due_andSilent_autoSends_whenEligible() {
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(50));
        haveOneFollowUp(f);
        when(emailTicketRepository.findById(42L)).thenReturn(Optional.of(originalRfq()));
        when(aiDraftingService.generateQuoteFollowUp(any())).thenReturn("Following up.");
        when(autoSendService.shouldAutoSend(any())).thenReturn(true);

        scheduler.pollFollowUps();

        verify(emailTicketRepository).save(any(EmailTicket.class));
        verify(autoSendService).autoSend(99L);
        assertFalse(f.isActive());
    }

    @Test
    void notDueYet_isSilent() {
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(1)); // well within the delay
        haveOneFollowUp(f);

        scheduler.pollFollowUps();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
        verify(followUpRepository, never()).save(any());
        assertTrue(f.isActive());
    }

    @Test
    void customerReplied_satisfiesWithoutNudge() {
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(50));
        haveOneFollowUp(f);
        when(emailTicketRepository.existsInboundReplyFrom(eq("customer@acme.com"), any())).thenReturn(true);

        scheduler.pollFollowUps();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
        assertFalse(f.isActive(), "a reply satisfies the follow-up");
        verify(followUpRepository).save(f);
    }

    @Test
    void originalTicketGone_deactivatesWithoutNudge() {
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(50));
        haveOneFollowUp(f);
        when(emailTicketRepository.findById(42L)).thenReturn(Optional.empty());

        scheduler.pollFollowUps();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
        assertFalse(f.isActive());
        verify(followUpRepository).save(f);
    }

    @Test
    void featureFlagOff_doesNothing() {
        when(settingsService.isFeatureEnabled(eq(QuoteFollowUpScheduler.FEATURE_KEY), eq(false)))
                .thenReturn(false);
        QuoteFollowUp f = followUp(10L, LocalDateTime.now().minusHours(50));
        when(followUpRepository.findByActiveTrue()).thenReturn(List.of(f));

        scheduler.pollFollowUps();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void noActiveFollowUps_returnsEarly() {
        when(followUpRepository.findByActiveTrue()).thenReturn(List.of());

        scheduler.pollFollowUps();

        verifyNoInteractions(aiDraftingService, autoSendService);
        verify(emailTicketRepository, never()).save(any());
    }
}
