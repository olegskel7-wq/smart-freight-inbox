package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.EmailClassificationService.ClassificationResult;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    @Mock
    private SlaService slaService;

    @Mock
    private AutoSendService autoSendService;

    private TicketProcessingService service;

    @BeforeEach
    void setUp() {
        service = new TicketProcessingService(
                emailTicketRepository, emailClassificationService, aiExtractionService,
                itemLookupService, aiDraftingService, rateSheetService, gmailDraftService,
                slaService, autoSendService, new SimpleMeterRegistry());
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
    void receivedTicket_classifiedIrrelevant_parkedWithoutPipeline_andSlaCleared() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        ticket.setSlaDeadlineAt(ticket.getCreatedAt().plusHours(8)); // provisional, set at ingest
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.classify(any(), any()))
                .thenReturn(new ClassificationResult(false, null));

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IRRELEVANT);
        assertThat(ticket.getTicketType()).isNull();
        assertThat(ticket.getSlaDeadlineAt()).isNull();
        verify(emailTicketRepository).save(ticket);
        verifyNoInteractions(aiExtractionService, itemLookupService, aiDraftingService, gmailDraftService);
    }

    @Test
    void receivedTicket_classifiedRelevant_runsFullPipelineToDraftReady_andSlaRefined() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.classify(eq("Container inquiry"), anyString()))
                .thenReturn(new ClassificationResult(true, TicketType.STATUS_UPDATE));
        when(slaService.isEnabled()).thenReturn(true);
        when(slaService.deadlineFor(TicketType.STATUS_UPDATE, ticket.getCreatedAt()))
                .thenReturn(ticket.getCreatedAt().plusHours(4));
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(ticket.getTicketType()).isEqualTo(TicketType.STATUS_UPDATE);
        assertThat(ticket.getSlaDeadlineAt()).isEqualTo(ticket.getCreatedAt().plusHours(4));
        assertThat(ticket.getDraftReply()).isNotBlank();
        verify(emailClassificationService).classify(eq("Container inquiry"), anyString());
    }

    @Test
    void receivedTicket_relevantWithoutType_featureOff_typeStaysNull() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.classify(any(), any()))
                .thenReturn(new ClassificationResult(true, null));
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(ticket.getTicketType()).isNull();
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

        boolean eligible = service.processAttempt(1L, System.nanoTime());

        assertThat(eligible).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(emailTicketRepository, never()).save(any());
        verifyNoInteractions(emailClassificationService, aiExtractionService, aiDraftingService);
    }

    // ---- Phase 1.6: confidence + auto-send wiring -------------------------------------

    @Test
    void draftReady_confidenceStored_notEligible_savesGmailDraft() {
        EmailTicket ticket = ticket(TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubHappyPipeline();
        when(autoSendService.confidenceFor(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(0.5);
        when(autoSendService.shouldAutoSend(ticket)).thenReturn(false);

        boolean eligible = service.processAttempt(1L, System.nanoTime());

        assertThat(eligible).isFalse();
        assertThat(ticket.getAiConfidence()).isEqualTo(0.5);
        verify(gmailDraftService).saveDraft(ticket);
    }

    @Test
    void draftReady_eligible_skipsGmailDraft_andReportsToCaller() {
        EmailTicket ticket = ticket(TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubHappyPipeline();
        when(autoSendService.confidenceFor(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(0.9);
        when(autoSendService.shouldAutoSend(ticket)).thenReturn(true);

        boolean eligible = service.processAttempt(1L, System.nanoTime());

        assertThat(eligible).isTrue();
        assertThat(ticket.getAiConfidence()).isEqualTo(0.9);
        // The SMTP send itself must NOT happen inside the pipeline transaction.
        verify(autoSendService, never()).autoSend(any());
        verify(gmailDraftService, never()).saveDraft(any());
    }
}
