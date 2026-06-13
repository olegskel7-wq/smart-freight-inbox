package com.smartfreight.inbox.scheduler;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.ShipmentWatch;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import com.smartfreight.inbox.repository.ShipmentWatchRepository;
import com.smartfreight.inbox.service.AiDraftingService;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.ItemLookupService;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StatusWatchScheduler} — the proactive milestone poller (Phase 2.4).
 *
 * <p>The static {@code TenantContext} helpers run their lambdas inline, and the mocked
 * {@link TransactionTemplate} simply invokes the callback — so the scheduler's branching logic
 * (milestone vs intermediate, change vs no-change, delivered terminal, flag gate) is exercised
 * directly without a database.
 */
@ExtendWith(MockitoExtension.class)
class StatusWatchSchedulerTest {

    @Mock private ShipmentWatchRepository watchRepository;
    @Mock private EmailTicketRepository emailTicketRepository;
    @Mock private ItemLookupService itemLookupService;
    @Mock private AiDraftingService aiDraftingService;
    @Mock private AutoSendService autoSendService;
    @Mock private SettingsService settingsService;
    @Mock private TransactionTemplate transactionTemplate;

    private StatusWatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Run any TransactionCallback inline against a null status (the code never uses it).
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        // Default: feature on, standard milestones. Individual tests override as needed.
        lenient().when(settingsService.isFeatureEnabled(eq(StatusWatchScheduler.FEATURE_KEY), eq(false)))
                .thenReturn(true);
        lenient().when(settingsService.getString(eq(StatusWatchScheduler.KEY_MILESTONES), anyString()))
                .thenReturn(StatusWatchScheduler.DEFAULT_MILESTONES);
        // Saving a proactive ticket assigns its id (real JPA behaviour) so auto-send gets one.
        lenient().when(emailTicketRepository.save(any(EmailTicket.class))).thenAnswer(inv -> {
            EmailTicket t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });
        scheduler = new StatusWatchScheduler(watchRepository, emailTicketRepository, itemLookupService,
                aiDraftingService, autoSendService, settingsService, transactionTemplate,
                new SimpleMeterRegistry());
    }

    private ShipmentWatch watch(Long id, String identifier, String lastNotified) {
        ShipmentWatch w = new ShipmentWatch();
        w.setId(id);
        w.setTenantId(1L);
        w.setIdentifier(identifier);
        w.setCustomerEmail("customer@acme.com");
        w.setLastNotifiedStatus(lastNotified);
        w.setActive(true);
        return w;
    }

    private TrackedItem item(String status) {
        TrackedItem i = new TrackedItem();
        i.setIdentifier("MSCU1234567");
        i.setStatus(status);
        return i;
    }

    private void haveOneWatch(ShipmentWatch w, TrackedItem polled) {
        when(watchRepository.findByActiveTrue()).thenReturn(List.of(w));
        when(watchRepository.findById(w.getId())).thenReturn(Optional.of(w));
        when(itemLookupService.findByIdentifier(w.getIdentifier())).thenReturn(Optional.ofNullable(polled));
    }

    @Test
    void milestoneChange_leftAsDraft_whenAutoSendDeclines() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "IN_TRANSIT");
        haveOneWatch(w, item("RELEASED"));
        when(aiDraftingService.generateProactiveUpdate(any(), eq("IN_TRANSIT"))).thenReturn("Your shipment is released.");
        when(autoSendService.shouldAutoSend(any())).thenReturn(false);

        scheduler.pollWatches();

        ArgumentCaptor<EmailTicket> captor = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(captor.capture());
        EmailTicket ticket = captor.getValue();
        assertTrue(ticket.isProactive());
        assertEquals(TicketType.STATUS_UPDATE, ticket.getTicketType());
        assertEquals(TicketStatus.DRAFT_READY, ticket.getStatus());
        assertEquals("customer@acme.com", ticket.getSenderEmail());
        assertEquals("MSCU1234567", ticket.getExtractedIdentifier());
        assertEquals("Your shipment is released.", ticket.getDraftReply());

        assertEquals("RELEASED", w.getLastNotifiedStatus());
        assertTrue(w.isActive());
        verify(watchRepository).save(w);
        verify(autoSendService, never()).autoSend(anyLong());
    }

    @Test
    void milestoneChange_autoSends_whenEligible() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "AT_PORT");
        haveOneWatch(w, item("RELEASED"));
        when(aiDraftingService.generateProactiveUpdate(any(), eq("AT_PORT"))).thenReturn("Released.");
        when(autoSendService.shouldAutoSend(any())).thenReturn(true);

        scheduler.pollWatches();

        verify(emailTicketRepository).save(any(EmailTicket.class));
        verify(autoSendService).autoSend(99L);
        assertEquals("RELEASED", w.getLastNotifiedStatus());
    }

    @Test
    void deliveredMilestone_deactivatesWatch() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "RELEASED");
        haveOneWatch(w, item("DELIVERED"));
        when(aiDraftingService.generateProactiveUpdate(any(), eq("RELEASED"))).thenReturn("Delivered.");
        when(autoSendService.shouldAutoSend(any())).thenReturn(false);

        scheduler.pollWatches();

        verify(emailTicketRepository).save(any(EmailTicket.class));
        assertEquals("DELIVERED", w.getLastNotifiedStatus());
        assertFalse(w.isActive(), "a delivered shipment ends the subscription");
    }

    @Test
    void intermediateStatus_isSilent() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "AT_PORT");
        haveOneWatch(w, item("CUSTOMS")); // not a milestone

        scheduler.pollWatches();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
        verify(watchRepository, never()).save(any());
        assertEquals("AT_PORT", w.getLastNotifiedStatus());
    }

    @Test
    void unchangedStatus_isSilent() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "RELEASED");
        haveOneWatch(w, item("RELEASED")); // same as last notified

        scheduler.pollWatches();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void featureFlagOff_doesNothing() {
        when(settingsService.isFeatureEnabled(eq(StatusWatchScheduler.FEATURE_KEY), eq(false)))
                .thenReturn(false);
        ShipmentWatch w = watch(10L, "MSCU1234567", "AT_PORT");
        when(watchRepository.findByActiveTrue()).thenReturn(List.of(w));

        scheduler.pollWatches();

        verifyNoInteractions(itemLookupService);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void itemNotFound_isSilent() {
        ShipmentWatch w = watch(10L, "MSCU1234567", "AT_PORT");
        haveOneWatch(w, null); // backend returned empty

        scheduler.pollWatches();

        verifyNoInteractions(aiDraftingService);
        verify(emailTicketRepository, never()).save(any());
    }

    @Test
    void noActiveWatches_returnsEarly() {
        when(watchRepository.findByActiveTrue()).thenReturn(List.of());

        scheduler.pollWatches();

        verifyNoInteractions(itemLookupService, aiDraftingService, emailTicketRepository);
    }
}
