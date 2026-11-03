package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketStatus;
import com.smartfreight.inbox.entity.enums.TicketType;
import com.smartfreight.inbox.repository.EmailTicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Characterisation tests for the parts of TicketProcessingService not pinned by
 * {@link TicketProcessingClassificationTest}: the retry loop in process() (auto-send after the
 * attempt, optimistic-lock retries, unexpected errors), the extraction / lookup edge cases, the
 * confidence inputs, the shipment-watch subscription, the attachment text in the AI input and
 * the bulk FAILED → NEW reset. They record today's behaviour as the safety net for splitting
 * processAttempt into steps (docs/refactoring.md, Stage 2).
 */
@ExtendWith(MockitoExtension.class)
class TicketProcessingPipelineTest {

    @Mock private EmailTicketRepository emailTicketRepository;
    @Mock private EmailClassificationService emailClassificationService;
    @Mock private AiExtractionService aiExtractionService;
    @Mock private ItemLookupService itemLookupService;
    @Mock private AiDraftingService aiDraftingService;
    @Mock private RateSheetService rateSheetService;
    @Mock private GmailDraftService gmailDraftService;
    @Mock private SlaService slaService;
    @Mock private AutoSendService autoSendService;
    @Mock private ShipmentWatchService shipmentWatchService;
    @Mock private DocumentRequestService documentRequestService;
    @Mock private FeedbackLoopService feedbackLoopService;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private EmbeddingService embeddingService;
    @Mock private SettingsService settingsService;
    @Mock private com.smartfreight.inbox.webhook.WebhookEventPublisher webhookEventPublisher;
    @Mock private TicketAuditService ticketAuditService;

    private SimpleMeterRegistry meterRegistry;
    private TicketProcessingService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new TicketProcessingService(
                emailTicketRepository, emailClassificationService,
                new IdentifierLookupStep(aiExtractionService, itemLookupService), aiDraftingService,
                new DraftContextAssembler(rateSheetService, feedbackLoopService, knowledgeBaseService, embeddingService),
                gmailDraftService, slaService, autoSendService, shipmentWatchService, documentRequestService, settingsService,
                webhookEventPublisher, ticketAuditService, meterRegistry);
        // process() calls through `self` (the Spring proxy in production); the plain instance is enough here.
        ReflectionTestUtils.setField(service, "self", service);
    }

    /** An already-typed ticket on the retry path, so classification is skipped. */
    private static EmailTicket typedTicket(TicketType type) {
        return EmailTicket.builder()
                .id(1L)
                .messageId("<msg-001@example.com>")
                .subject("Enquiry")
                .body("Status of MSCU1234567 please")
                .status(TicketStatus.NEW)
                .ticketType(type)
                .build();
    }

    private static TrackedItem item() {
        return TrackedItem.builder().identifier("MSCU1234567").status("AT_PORT").location("Felixstowe").build();
    }

    /** Generic (non-document) draft path with a configurable identifier and lookup result. */
    private void stubDraftPath(String identifier, Optional<TrackedItem> item, String rateContext) {
        when(aiExtractionService.extractIdentifier(anyString())).thenReturn(identifier);
        if (!identifier.isEmpty()) {
            when(itemLookupService.findByIdentifier(identifier)).thenReturn(item);
        }
        when(rateSheetService.buildRateContext(anyString())).thenReturn(rateContext);
        when(feedbackLoopService.retrieveExamples(any(), anyString(), any())).thenReturn("");
        when(knowledgeBaseService.retrieveContext(anyString(), any())).thenReturn("");
        when(aiDraftingService.generateDraft(any(ReplyDraftRequest.class)))
                .thenReturn("Dear customer, ...");
        when(autoSendService.confidenceFor(any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(0.5);
    }

    private double counter(String name, String tagKey, String tagValue) {
        return meterRegistry.get(name).tag(tagKey, tagValue).counter().count();
    }

    // ---- process(): what happens around one attempt ----------------------------------

    @Test
    void process_eligibleDraft_autoSendsAfterTheAttempt() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "");
        when(autoSendService.shouldAutoSend(ticket)).thenReturn(true);

        service.process(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(autoSendService).autoSend(1L);
        verify(gmailDraftService, never()).saveDraft(any());
    }

    @Test
    void process_notEligibleDraft_doesNotAutoSend() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "");
        when(autoSendService.shouldAutoSend(ticket)).thenReturn(false);

        service.process(1L);

        verify(autoSendService, never()).autoSend(any());
        verify(gmailDraftService).saveDraft(ticket);
    }

    @Test
    void process_optimisticLockOnEveryAttempt_triesThreeTimesThenMarksFailed() {
        // Each findById returns a fresh copy, as a real reload after a rolled-back attempt would.
        when(emailTicketRepository.findById(1L))
                .thenAnswer(inv -> Optional.of(typedTicket(TicketType.STATUS_UPDATE)));
        when(emailTicketRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(EmailTicket.class, 1L));

        service.process(1L);

        verify(emailTicketRepository, times(3)).saveAndFlush(any());
        ArgumentCaptor<EmailTicket> saved = ArgumentCaptor.forClass(EmailTicket.class);
        verify(emailTicketRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(counter("smartfreight_tickets_status_total", "status", "FAILED")).isEqualTo(1.0);
        verifyNoInteractions(aiExtractionService, aiDraftingService, autoSendService);
    }

    @Test
    void process_optimisticLockOnce_retriesAndSucceeds() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L))
                .thenReturn(Optional.of(typedTicket(TicketType.STATUS_UPDATE)))
                .thenReturn(Optional.of(ticket));
        when(emailTicketRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(EmailTicket.class, 1L))
                .thenAnswer(inv -> inv.getArgument(0));
        stubDraftPath("MSCU1234567", Optional.empty(), "");
        when(autoSendService.shouldAutoSend(ticket)).thenReturn(false);

        service.process(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(emailTicketRepository, times(2)).saveAndFlush(any());
        assertThat(counter("smartfreight_tickets_status_total", "status", "FAILED")).isZero();
    }

    @Test
    void process_unexpectedError_marksFailedWithoutRetrying() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(aiExtractionService.extractIdentifier(anyString())).thenThrow(new IllegalStateException("boom"));

        service.process(1L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.FAILED);
        verify(aiExtractionService, times(1)).extractIdentifier(anyString());
        verifyNoInteractions(aiDraftingService, autoSendService, gmailDraftService);
    }

    @Test
    void process_unknownTicket_isSwallowed_andNothingIsSaved() {
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.empty());

        service.process(1L);

        verify(emailTicketRepository, never()).save(any());
        verify(emailTicketRepository, never()).saveAndFlush(any());
        // markFailed found nothing to mark, so the FAILED counter stays at zero.
        assertThat(counter("smartfreight_tickets_status_total", "status", "FAILED")).isZero();
    }

    // ---- processAttempt(): extraction, lookup and confidence inputs -------------------

    @Test
    void retryLaterTicket_isProcessedLikeNew() {
        EmailTicket ticket = typedTicket(TicketType.GENERAL);
        ticket.setStatus(TicketStatus.RETRY_LATER);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "");

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
    }

    @Test
    void noIdentifierExtracted_storesNull_skipsLookup_andScoresWithoutIdentifier() {
        EmailTicket ticket = typedTicket(TicketType.GENERAL);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("", Optional.empty(), "");

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getExtractedIdentifier()).isNull();
        verifyNoInteractions(itemLookupService);
        verify(aiDraftingService).generateDraft(argThat(r -> r.item() == null
                && "".equals(r.rateContext()) && "".equals(r.exampleContext()) && "".equals(r.knowledgeContext())));
        verify(autoSendService).confidenceFor(TicketType.GENERAL, false, false, false);
    }

    @Test
    void itemFoundAndRateMatched_feedTheDraftAndTheConfidenceScore() {
        EmailTicket ticket = typedTicket(TicketType.RFQ);
        TrackedItem item = item();
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.of(item), "Rate: GBP 1200");

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getExtractedIdentifier()).isEqualTo("MSCU1234567");
        verify(aiDraftingService).generateDraft(argThat(r -> r.item() == item
                && "Rate: GBP 1200".equals(r.rateContext()) && "".equals(r.exampleContext())
                && "".equals(r.knowledgeContext())));
        verify(autoSendService).confidenceFor(TicketType.RFQ, true, true, true);
        assertThat(ticket.getAiConfidence()).isEqualTo(0.5);
        assertThat(ticket.getAiOriginalDraft()).isEqualTo(ticket.getDraftReply());
    }

    @Test
    void blankRateContext_doesNotCountAsRateMatched() {
        EmailTicket ticket = typedTicket(TicketType.RFQ);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "   ");

        service.processAttempt(1L, System.nanoTime());

        verify(autoSendService).confidenceFor(TicketType.RFQ, true, false, false);
    }

    /** RateSheetService never returns null today; the guard keeps a null from failing the draft. */
    @Test
    void nullRateContext_doesNotCountAsRateMatched() {
        EmailTicket ticket = typedTicket(TicketType.RFQ);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), null);

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.DRAFT_READY);
        verify(autoSendService).confidenceFor(TicketType.RFQ, true, false, false);
    }

    // ---- processAttempt(): proactive shipment watch (Phase 2.4) -----------------------

    @Test
    void statusUpdate_withIdentifierAndItem_armsShipmentWatch() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        TrackedItem item = item();
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.of(item), "");

        service.processAttempt(1L, System.nanoTime());

        verify(shipmentWatchService).upsertFromTicket(ticket, item);
    }

    @Test
    void statusUpdate_itemNotFound_armsNoWatch() {
        EmailTicket ticket = typedTicket(TicketType.STATUS_UPDATE);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "");

        service.processAttempt(1L, System.nanoTime());

        verifyNoInteractions(shipmentWatchService);
    }

    @Test
    void otherType_withItem_armsNoWatch() {
        EmailTicket ticket = typedTicket(TicketType.RFQ);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.of(item()), "");

        service.processAttempt(1L, System.nanoTime());

        verifyNoInteractions(shipmentWatchService);
    }

    // ---- processAttempt(): AI input = body + attachment text (Phase 1.1) ---------------

    @Test
    void attachmentText_isAppendedToTheBodyForEveryModel() {
        EmailTicket ticket = typedTicket(TicketType.RFQ);
        ticket.setAttachmentText("Packing list: 12 pallets");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("MSCU1234567", Optional.empty(), "");
        String expected = "Status of MSCU1234567 please\n\nPacking list: 12 pallets";

        service.processAttempt(1L, System.nanoTime());

        verify(aiExtractionService).extractIdentifier(expected);
        verify(rateSheetService).buildRateContext(expected);
        verify(aiDraftingService).generateDraft(argThat(r -> expected.equals(r.emailBody())));
    }

    @Test
    void nullBody_andBlankAttachment_giveAnEmptyAiInput() {
        EmailTicket ticket = typedTicket(TicketType.GENERAL);
        ticket.setBody(null);
        ticket.setAttachmentText("  ");
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubDraftPath("", Optional.empty(), "");

        service.processAttempt(1L, System.nanoTime());

        verify(aiExtractionService).extractIdentifier("");
    }

    // ---- processAttempt(): document request without an identifier (Phase 2.7) ---------

    @Test
    void documentRequest_noIdentifier_escalatesWithoutLookingForADocument() {
        EmailTicket ticket = typedTicket(TicketType.DOCUMENT_REQUEST);
        when(emailTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(aiExtractionService.extractIdentifier(anyString())).thenReturn("");
        when(rateSheetService.buildRateContext(anyString())).thenReturn("");
        when(settingsService.isFeatureEnabled(eq(DocumentService.FEATURE_KEY), anyBoolean())).thenReturn(true);
        when(documentRequestService.detectRequestedType(anyString())).thenReturn(DocumentType.POD);
        when(aiDraftingService.generateDocumentReply(anyString(), eq(DocumentType.POD), isNull(), eq(false)))
                .thenReturn("We will send it shortly.");
        when(autoSendService.confidenceFor(any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(0.5);

        service.processAttempt(1L, System.nanoTime());

        assertThat(ticket.getRequestedDocType()).isEqualTo(DocumentType.POD);
        assertThat(ticket.getAttachedDocumentId()).isNull();
        verify(documentRequestService, never()).findDocument(anyString(), any());
        assertThat(counter("smartfreight_document_requests_total", "result", "escalated")).isEqualTo(1.0);
    }

    // ---- prepareForRetry(): bulk FAILED → NEW ------------------------------------------

    @Test
    void prepareForRetry_resetsFailedToNew_andReturnsTheCount() {
        when(emailTicketRepository.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED)).thenReturn(4);

        assertThat(service.prepareForRetry()).isEqualTo(4);
    }

    @Test
    void prepareForRetry_nothingToReset_returnsZero() {
        when(emailTicketRepository.resetFailedToNew(TicketStatus.NEW, TicketStatus.FAILED)).thenReturn(0);

        assertThat(service.prepareForRetry()).isZero();
    }
}
