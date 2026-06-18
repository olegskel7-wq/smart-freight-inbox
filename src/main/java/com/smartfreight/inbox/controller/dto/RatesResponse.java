package com.smartfreight.inbox.controller.dto;

import java.util.List;

/**
 * Snapshot of the current tenant's carrier rate sheet for the React {@code /rates} page (Phase 3.2).
 * {@code featureEnabled} mirrors {@code feature.rate-sheets}: when off, rates are stored but never
 * injected into drafts, so the SPA shows a warning badge.
 */
public record RatesResponse(boolean featureEnabled, List<RateDto> rates) {
}
