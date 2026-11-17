package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TenantDocument;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.DocumentType;
import com.smartfreight.inbox.entity.enums.TicketType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Pipeline step 3 of {@link TicketProcessingService}: the reply draft (Llama 3.1).
 *
 * A document request takes the Phase 2.7 path (detect the wanted document, attach it if on file,
 * else escalate); everything else gets the generic draft grounded by {@link DraftContextAssembler}.
 * Sets the draft, the unmodified AI draft, the document fields and the confidence score on the ticket;
 * the orchestrator moves the status and saves. Runs inside the orchestrator's transaction; open-breaker
 * exceptions propagate.
 */
@Component
class ReplyDraftStep {

    private static final Logger log = LoggerFactory.getLogger(ReplyDraftStep.class);

    private final SettingsService settingsService;
    private final DocumentRequestService documentRequestService;
    private final AiDraftingService aiDraftingService;
    private final DraftContextAssembler draftContextAssembler;
    private final AutoSendService autoSendService;
    private final Counter documentAttachedCounter;
    private final Counter documentEscalatedCounter;

    ReplyDraftStep(SettingsService settingsService,
                   DocumentRequestService documentRequestService,
                   AiDraftingService aiDraftingService,
                   DraftContextAssembler draftContextAssembler,
                   AutoSendService autoSendService,
                   MeterRegistry meterRegistry) {
        this.settingsService = settingsService;
        this.documentRequestService = documentRequestService;
        this.aiDraftingService = aiDraftingService;
        this.draftContextAssembler = draftContextAssembler;
        this.autoSendService = autoSendService;
        this.documentAttachedCounter = Counter.builder("smartfreight_document_requests_total")
                .tag("result", "attached")
                .description("Document requests resolved by the Phase 2.7 pipeline")
                .register(meterRegistry);
        this.documentEscalatedCounter = Counter.builder("smartfreight_document_requests_total")
                .tag("result", "escalated")
                .description("Document requests resolved by the Phase 2.7 pipeline")
                .register(meterRegistry);
    }

    /**
     * @param identifier the extracted identifier, empty when none
     * @param item       the backend item, {@code null} when not found
     */
    void draft(EmailTicket ticket, String aiInput, String identifier, TrackedItem item) {
        String rateContext = draftContextAssembler.rateContext(aiInput);

        String draft;
        // A document request takes a dedicated path — determine which document is wanted,
        // attach it if on file (it then rides the same auto-send/operator gate as any reply), else
        // escalate: draft an acknowledgement and flag the requested type for the operator. The
        // detection call propagates an open-breaker exception, so a parked ticket re-runs cleanly.
        if (ticket.getTicketType() == TicketType.DOCUMENT_REQUEST
                && settingsService.isFeatureEnabled(DocumentService.FEATURE_KEY, true)) {
            DocumentType requested = documentRequestService.detectRequestedType(aiInput);
            ticket.setRequestedDocType(requested);
            Optional<TenantDocument> doc = identifier.isEmpty()
                    ? Optional.empty()
                    : documentRequestService.findDocument(identifier, requested);
            if (doc.isPresent()) {
                ticket.setAttachedDocumentId(doc.get().getId());
                draft = aiDraftingService.generateDocumentReply(aiInput, requested, item, true);
                documentAttachedCounter.increment();
                log.info("Document request: attaching {} for '{}'", requested, identifier);
            } else {
                draft = aiDraftingService.generateDocumentReply(aiInput, requested, item, false);
                documentEscalatedCounter.increment();
                log.info("Document request: {} not on file for '{}' — escalating to operator",
                        requested, identifier);
            }
        } else {
            draft = aiDraftingService.generateDraft(
                    draftContextAssembler.replyRequest(ticket.getTicketType(), aiInput, identifier, item,
                            rateContext));
        }
        ticket.setDraftReply(draft);
        // Preserve the unmodified AI draft so the feedback loop can compare it against
        // what the operator actually sends. draftReply gets overwritten on edit; this does not.
        ticket.setAiOriginalDraft(draft);

        // Confidence is computed and stored on every run (even with auto-send off)
        // so the score distribution accumulates for threshold tuning.
        ticket.setAiConfidence(autoSendService.confidenceFor(
                ticket.getTicketType(),
                ticket.getExtractedIdentifier() != null,
                item != null,
                rateContext != null && !rateContext.isBlank()));
    }
}
