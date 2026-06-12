package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.service.EmailClassificationService.ClassificationResult;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
 * - NEW (retry re-entry), binary mode     → classifier deliberately skipped (fail-open)
 * - NEW + null type, typed mode (1.7)     → classifier re-entered (first verdict, not a re-judgement)
 * - terminal status                       → no-op, nothing touched
 * - CallNotPermittedException (1.7)       → process() parks the ticket as RETRY_LATER, not FAILED
 *
 * processAttempt is called directly (it is public) — the self-proxy retry loop in
 * process() is orthogonal to most of what is tested here; the park tests go through
 * process() with the self-reference injected without a proxy.
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
        // In production `self` is the Spring proxy (@Transactional); in a unit test the
        // plain instance is enough — transactional semantics are not under test here.
        ReflectionTestUtils.setField(service, "self", service);
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
    void newTicket_retryReentry_binaryMode_skipsClassification() {
        EmailTicket ticket = ticket(TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        // Binary mode persists no classification verdict — the historical fail-open skip holds.
        when(emailClassificationService.isTypedMode()).thenReturn(false);
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(emailClassificationService, never()).classify(any(), any());
    }

    @Test
    void newTicket_retryReentry_typedModeAndNoType_reentersClassification() {
        // Phase 1.7: a ticket parked by an open breaker before step 0 completed re-enters
        // classification on retry — null type in typed mode marks "never got a verdict".
        EmailTicket ticket = ticket(TicketStatus.NEW);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.isTypedMode()).thenReturn(true);
        when(emailClassificationService.classify(any(), any()))
                .thenReturn(new ClassificationResult(true, TicketType.RFQ));
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(ticket.getTicketType()).isEqualTo(TicketType.RFQ);
    }

    @Test
    void newTicket_retryReentry_typedModeButTypeAlreadySet_skipsClassification() {
        // An operator-redraft ticket carries its verdict — it must not be re-judged.
        EmailTicket ticket = ticket(TicketStatus.NEW);
        ticket.setTicketType(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubHappyPipeline();

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        assertThat(ticket.getTicketType()).isEqualTo(TicketType.STATUS_UPDATE);
        verify(emailClassificationService, never()).classify(any(), any());
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

    // ---- Phase 1.7: circuit breaker open → park as RETRY_LATER ------------------------

    private static CallNotPermittedException breakerOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("ollama");
        breaker.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    @Test
    void process_breakerOpenDuringExtraction_parksTicketAsRetryLater_keepsRetryBudget() {
        EmailTicket ticket = ticket(TicketStatus.NEW);
        ticket.setRetryCount(2);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.isTypedMode()).thenReturn(false);
        when(aiExtractionService.extractIdentifier(anyString())).thenThrow(breakerOpen());

        service.process(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RETRY_LATER);
        // Parking is not the ticket's fault — the retry budget must stay untouched.
        assertThat(ticket.getRetryCount()).isEqualTo(2);
        verifyNoInteractions(aiDraftingService, gmailDraftService, autoSendService);
    }

    @Test
    void process_breakerOpenDuringClassification_parksReceivedTicket() {
        EmailTicket ticket = ticket(TicketStatus.RECEIVED);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(emailClassificationService.classify(any(), any())).thenThrow(breakerOpen());

        service.process(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RETRY_LATER);
        verifyNoInteractions(aiExtractionService, aiDraftingService, gmailDraftService);
    }
}
