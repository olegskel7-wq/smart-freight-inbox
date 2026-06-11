package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the classification branch of TicketProcessingService.processAttempt
 * (Phase 1.3 session 3 — classification moved off the IMAP thread into the consumer).
 *
 * Verifies:
 * - RECEIVED + classifier says irrelevant → ticket parked as IRRELEVANT, pipeline never runs
 * - RECEIVED + classifier says relevant   → full pipeline runs to DRAFT_READY
 * - NEW (retry re-entry)                  → classifier deliberately skipped (fail-open)
 * - terminal status                       → no-op, nothing touched
 *
 * processAttempt is called directly (it is public) — the self-proxy retry loop in
 * process() is orthogonal to what is tested here.
 */
@ExtendWith(MockitoExtension.class)
class TicketProcessingClassificationTest {

    @Mock
    private EmailTicketRepository emailTicketRepository;

    @Mock
    private EmailClassificationService emailClassificationService;

    @Mock
    private AiExtractionService aiExtractionService;

    @Mock
    private ItemLookupService itemLookupService;

    @Mock
    private AiDraftingService aiDraftingService;

    @Mock
    private RateSheetService rateSheetService;

    @Mock
    private GmailDraftService gmailDraftService;

    private TicketProcessingService service;

    @BeforeEach
    void setUp() {
        service = new TicketProcessingService(
                emailTicketRepository, emailClassificationService, aiExtractionService,
                itemLookupService, aiDraftingService, rateSheetService, gmailDraftService,
                new SimpleMeterRegistry());
    }

    private EmailTicket ticket(TicketStatus status) {
        return EmailTicket.builder()
                .id(1L)
                .messageId("<msg-001@example.com>")
                .subject("Container inquiry")
                .body("Status of MSCU1234567 please")
                .status(status)
                .build();
    }

    private void stubHappyPipeline() {
        when(aiExtractionService.extractIdentifier(anyString())).thenReturn("MSCU1234567");
        when(itemLookupService.findByIdentifier("MSCU1234567")).thenReturn(Optional.empty());
        when(rateSheetService.buildRateContext(anyString())).thenReturn("");
        when(aiDraftingService.generateDraft(anyString(), any(), anyString())).thenReturn("Dear customer, ...");
    }

    @Test
    void receivedTicket_classifiedIrrelevant_parkedWithoutPipeline() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.isFreightInquiry(any(), any())).thenReturn(false);

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IRRELEVANT);
        verify(emailTicketRepository).save(ticket);
        verifyNoInteractions(aiExtractionService, itemLookupService, aiDraftingService, gmailDraftService);
    }

    @Test
    void receivedTicket_classifiedRelevant_runsFullPipelineToDraftReady() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.isFreightInquiry(eq("Container inquiry"), anyString()))
                .thenReturn(true);
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(ticket.getDraftReply()).isNotBlank();
        verify(emailClassificationService).isFreightInquiry(eq("Container inquiry"), anyString());
    }

    @Test
    void newTicket_retryReentry_skipsClassification() {
        EmailTicket ticket = ticket(TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verifyNoInteractions(emailClassificationService);
    }

    @Test
    void terminalStatus_isIdempotentNoOp() {
        EmailTicket ticket = ticket(TicketStatus.DRAFT_READY);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(emailClassificationService, aiExtractionService, aiDraftingService);
    }
}
