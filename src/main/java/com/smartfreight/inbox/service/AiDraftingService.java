package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.EmailTicket;
import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.DocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Reply draft generation service using Llama 3.1.
 *
 * Receives the email body and container data →
 * returns a ready-to-review draft for the operator.
 *
 * Each draft kind picks its tenant prompt ({@link DraftPrompts}), its static fallback
 * ({@link DraftFallbacks}) and goes through the shared Ollama call ({@link OllamaDraftClient}).
 * If Ollama is unavailable or returns an empty response, the fallback is used with data from the DB.
 */
@Service
public class AiDraftingService {

    private final OllamaDraftClient draftClient;
    private final SettingsService settingsService;
    private final Clock clock;

    @Autowired
    public AiDraftingService(OllamaDraftClient draftClient, SettingsService settingsService) {
        this(draftClient, settingsService, Clock.systemDefaultZone());
    }

    /** Test seam: a fixed clock makes the stale-ETA flag in {@link #buildItemContext} deterministic. */
    AiDraftingService(OllamaDraftClient draftClient, SettingsService settingsService, Clock clock) {
        this.draftClient = draftClient;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * Generates a professional reply draft for a customer email.
     *
     * Executes POST /api/generate to Ollama with the tenant's {@code ai.drafting-prompt} filled with
     * the email text (up to 2000 characters), the item data and the request's rate, few-shot example
     * (see docs/adr/0010-rag-feedback-and-knowledge.md) and knowledge context.
     *
     * The model is instructed to reply in the same language as the customer
     * and not to use information that is not present in the item data.
     *
     * @param request the enquiry and its context blocks
     * @return generated or fallback draft
     */
    public String generateDraft(ReplyDraftRequest request) {
        // "Item not found" is true only after a failed lookup. An email that named no reference (a quote
        // request, a general question) gets a note saying so, or the model apologises for a missing
        // container nobody asked about.
        boolean lookupSkipped = request.lookupSkipped();
        String itemContext = lookupSkipped ? DraftPrompts.NO_REFERENCE_NOTE : buildItemContext(request.item());
        String template = settingsService.getString(
                SettingsService.KEY_DRAFTING_PROMPT, DraftPrompts.DEFAULT_DRAFTING_PROMPT);
        String prompt = DraftPrompts.reply(template, request, itemContext);
        return draftClient.generate(prompt,
                lookupSkipped ? DraftFallbacks.NO_REFERENCE : DraftFallbacks.forItem(request.item()));
    }

    /**
     * Generates a proactive status-update notification.
     *
     * <p>Unlike {@link #generateDraft}, this is not a reply to an inbound email — it is composed
     * when a watched shipment reaches a milestone, so it uses the dedicated
     * {@code ai.status-update-prompt} template ({@code {itemContext}}, {@code {previousStatus}})
     * rather than the reply prompt. The Ollama call, per-tenant endpoint, circuit breaker and
     * fallback are shared with the reply path.
     *
     * @param item           the freshly looked-up shipment (its current status is the milestone)
     * @param previousStatus the status the customer was last told about (may be null)
     * @return generated or fallback notification body
     */
    public String generateProactiveUpdate(TrackedItem item, String previousStatus) {
        String itemContext = buildItemContext(item);
        String template = settingsService.getString(
                SettingsService.KEY_STATUS_UPDATE_PROMPT, DraftPrompts.DEFAULT_STATUS_UPDATE_PROMPT);
        String prompt = DraftPrompts.statusUpdate(template, itemContext, previousStatus);
        return draftClient.generate(prompt, DraftFallbacks.forItem(item));
    }

    /**
     * Generates a proactive quote follow-up nudge.
     *
     * <p>Composed when a sent quote has gone unanswered for the configured delay — it is not a
     * reply to inbound mail, so it uses the dedicated {@code ai.quote-followup-prompt} template
     * ({@code {originalEnquiry}}) rather than the reply prompt, and a quote-specific static
     * fallback (there is no shipment item to fall back on). The Ollama call, per-tenant endpoint,
     * circuit breaker and breaker propagation are shared with the reply path.
     *
     * @param originalTicket the quoted RFQ ticket (its body is the original enquiry context)
     * @return generated or fallback nudge body
     */
    public String generateQuoteFollowUp(EmailTicket originalTicket) {
        String template = settingsService.getString(
                SettingsService.KEY_QUOTE_FOLLOWUP_PROMPT, DraftPrompts.DEFAULT_QUOTE_FOLLOWUP_PROMPT);
        String prompt = DraftPrompts.quoteFollowUp(template, originalTicket.getBody());
        return draftClient.generate(prompt, DraftFallbacks.QUOTE_FOLLOW_UP);
    }

    /**
     * Generates a reply to a document request.
     *
     * <p>Uses the dedicated {@code ai.document-cover-prompt} template — the same shared Ollama
     * call, per-tenant endpoint, breaker and fallback as the reply path. The {@code attached} flag
     * picks the {@code {documentStatus}} wording (and the static fallback) so one prompt serves both
     * branches: the document is attached, or it is being retrieved (escalation).
     *
     * @param emailBody    customer email text (body + parsed attachments)
     * @param documentType the detected requested document type
     * @param item         looked-up shipment data (may be null if not found)
     * @param attached     whether a matching document was found and will be attached
     * @return generated or fallback reply body
     */
    public String generateDocumentReply(String emailBody, DocumentType documentType,
                                        TrackedItem item, boolean attached) {
        String itemContext = buildItemContext(item);
        String docLabel = documentType.getLabel();
        String template = settingsService.getString(
                SettingsService.KEY_DOCUMENT_COVER_PROMPT, DraftPrompts.DEFAULT_DOCUMENT_COVER_PROMPT);
        String prompt = DraftPrompts.documentReply(template, docLabel, attached, itemContext, emailBody);
        return draftClient.generate(prompt, DraftFallbacks.forDocument(docLabel, attached));
    }

    /** Item block for the prompt, see {@link DraftPrompts#itemContext}. */
    String buildItemContext(TrackedItem item) {
        return DraftPrompts.itemContext(item, LocalDateTime.now(clock));
    }
}
