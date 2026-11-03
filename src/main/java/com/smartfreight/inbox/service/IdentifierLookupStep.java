package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrackedItem;
import org.springframework.stereotype.Component;

/**
 * Pipeline steps 1–2 of {@link TicketProcessingService}: extract the item identifier from the
 * enquiry (Qwen 2.5), then look the item up in the tenant's backend ({@link ItemLookupService}).
 *
 * Two separate calls on purpose — the orchestrator stores the identifier on the ticket between them.
 * Runs inside the orchestrator's transaction; an open-breaker exception from extraction propagates
 * so the ticket is parked, exactly as before the split.
 */
@Component
class IdentifierLookupStep {

    private final AiExtractionService aiExtractionService;
    private final ItemLookupService itemLookupService;

    IdentifierLookupStep(AiExtractionService aiExtractionService, ItemLookupService itemLookupService) {
        this.aiExtractionService = aiExtractionService;
        this.itemLookupService = itemLookupService;
    }

    /** @return the extracted identifier, or an empty string when the enquiry names none */
    String extract(String aiInput) {
        return aiExtractionService.extractIdentifier(aiInput);
    }

    /** @return the backend item, or {@code null} when there is no identifier or no match */
    TrackedItem lookup(String identifier) {
        if (identifier.isEmpty()) {
            return null;
        }
        return itemLookupService.findByIdentifier(identifier).orElse(null);
    }
}
