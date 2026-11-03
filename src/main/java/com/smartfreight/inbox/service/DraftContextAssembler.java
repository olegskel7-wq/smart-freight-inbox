package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import com.smartfreight.inbox.entity.enums.TicketType;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Context assembly for the reply draft in {@link TicketProcessingService}: the matched carrier rates,
 * the tenant's past operator corrections (few-shot examples) and its knowledge base.
 *
 * Each retriever owns its feature gate and degrades to an empty string on its own, so the pipeline
 * stays out of those decisions. Runs inside the orchestrator's transaction.
 */
@Component
class DraftContextAssembler {

    private final RateSheetService rateSheetService;
    private final FeedbackLoopService feedbackLoopService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingService embeddingService;

    DraftContextAssembler(RateSheetService rateSheetService,
                          FeedbackLoopService feedbackLoopService,
                          KnowledgeBaseService knowledgeBaseService,
                          EmbeddingService embeddingService) {
        this.rateSheetService = rateSheetService;
        this.feedbackLoopService = feedbackLoopService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingService = embeddingService;
    }

    /** Matched carrier rates (if any) let the model quote a real price; a match also raises the confidence score. */
    String rateContext(String aiInput) {
        return rateSheetService.buildRateContext(aiInput);
    }

    /**
     * Builds the generic (non-document) draft request: the item, the rates, plus the retrieved
     * examples and knowledge.
     */
    ReplyDraftRequest replyRequest(TicketType ticketType, String aiInput, TrackedItem item, String rateContext) {
        // Both the feedback loop and the knowledge base rank by semantic
        // similarity to the SAME enquiry, so share ONE memoised embedding supplier — the enquiry is
        // embedded at most once per draft, lazily, and only if a retriever (after its own feature /
        // blank checks) actually needs the vector. embed() never returns null, so SingletonSupplier
        // caches the first answer, an empty Optional included.
        Supplier<Optional<float[]>> enquiryEmbedding =
                SingletonSupplier.of(() -> embeddingService.embed(aiInput));
        // Inject the most similar past operator corrections (this tenant, this type)
        // as few-shot examples so the draft improves as corrections accumulate.
        String exampleContext = feedbackLoopService.retrieveExamples(ticketType, aiInput, enquiryEmbedding);
        // Ground the draft with the tenant's knowledge base (FAQs / policies / rules) so
        // typical "how / where / when" enquiries get a substantive answer even without backend data.
        String knowledgeContext = knowledgeBaseService.retrieveContext(aiInput, enquiryEmbedding);
        return new ReplyDraftRequest(aiInput, item, rateContext, exampleContext, knowledgeContext);
    }
}
